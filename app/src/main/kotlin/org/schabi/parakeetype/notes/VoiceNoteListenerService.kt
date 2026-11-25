package org.schabi.parakeetype.notes

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Environment
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.schabi.parakeetype.R
import org.schabi.parakeetype.audio.AudioFileDecoder
import org.schabi.parakeetype.audio.ClientAudioReader
import org.schabi.parakeetype.audio.PcmFormat
import org.schabi.parakeetype.audio.VadFilter
import org.schabi.parakeetype.inference.TextReplacements
import org.schabi.parakeetype.inference.TranscriptResult
import org.schabi.parakeetype.recognition.InferenceConnection
import org.schabi.parakeetype.recognition.TranscriptAccumulator
import org.schabi.parakeetype.settings.preferences.AppPreferences
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

private const val TAG = "VoiceNoteListener"

private const val CHANNEL_ID = "voice_notes"

/** How long to wait for the messenger to finish downloading a voice note. */
private const val FIND_ATTEMPTS = 10
private const val FIND_INTERVAL_MS = 2_000L

/**
 * How long the binding to the inference service is kept after the last voice note, so a burst
 * of notes loads the model once.
 */
private const val UNBIND_AFTER_MS = 120_000L

/**
 * Transcribes incoming WhatsApp voice notes and posts the text as a notification.
 *
 * Switched on by granting Parakeetype notification access. On a WhatsApp voice-message
 * notification it finds the note's file in WhatsApp's Voice Notes folder (which needs "All
 * files access": WhatsApp's `.nomedia` hides the files from MediaStore), decodes it and runs
 * it through the same pipeline as the speech recognizer.
 *
 * Notification access delivers every app's notifications; everything not from
 * [VOICE_NOTE_PACKAGES] is dropped before it is read. Logs never contain names, message
 * text, transcriptions or file names.
 */
class VoiceNoteListenerService : NotificationListenerService() {

    private class Request(
        val packageName: String,
        val message: VoiceMessage,
        val title: String,
        val contentIntent: PendingIntent?,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requests = Channel<Request>(Channel.UNLIMITED)
    private val inference by lazy { InferenceConnection(this) }
    private val store by lazy { VoiceNoteStore(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        scope.launch { processRequests() }
    }

    override fun onDestroy() {
        scope.cancel()
        inference.unbind()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        store.enabledAtMs()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in VOICE_NOTE_PACKAGES) return
        val style = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(sbn.notification) ?: return
        val messages = style.messages.map {
            NotificationMessage(it.text?.toString(), it.timestamp, it.person?.name?.toString())
        }
        val conversation = sbn.key
        val voiceMessages = newVoiceMessages(messages, store.seenUntilMs(conversation))
        messages.maxOfOrNull { it.timeMs }?.let { newest ->
            if (newest > store.seenUntilMs(conversation)) store.setSeenUntil(conversation, newest)
        }
        if (voiceMessages.isEmpty()) return
        Log.d(TAG, "${voiceMessages.size} new voice message(s)")

        val chat = style.conversationTitle?.toString()
        for (message in voiceMessages) {
            val sender = message.sender ?: chat ?: getString(R.string.voice_note_unknown_sender)
            val title = if (style.isGroupConversation && chat != null && chat != sender) {
                getString(R.string.voice_note_title_group, sender, chat)
            } else {
                sender
            }
            requests.trySend(Request(sbn.packageName, message, title, sbn.notification.contentIntent))
        }
    }

    /** Handles the voice notes one at a time; unbinds the inference service when idle. */
    private suspend fun processRequests() {
        while (true) {
            val request = withTimeoutOrNull(UNBIND_AFTER_MS) { requests.receive() }
            if (request == null) {
                withContext(Dispatchers.Main) { inference.unbind() }
                handle(requests.receive())
            } else {
                handle(request)
            }
        }
    }

    private suspend fun handle(request: Request) {
        if (!Environment.isExternalStorageManager()) {
            Log.w(TAG, "No all-files access: can't read voice notes")
            notify(request, getString(R.string.voice_note_no_file_access))
            return
        }
        val file = findFile(request) ?: return
        store.markDone(file.path)

        val result = withContext(Dispatchers.Main) { inference.awaitRepository() }
        val repository = when (result) {
            is InferenceConnection.Result.Ready -> result.repository
            is InferenceConnection.Result.Unavailable -> {
                Log.w(TAG, "Engine unavailable: ${result.reason}")
                notify(request, getString(R.string.voice_note_failed))
                return
            }
        }

        val pcm = try {
            withContext(Dispatchers.IO) { AudioFileDecoder.decodeToPipelinePcm(file.path) }
        } catch (e: IOException) {
            Log.w(TAG, "Decoding the voice note failed: ${e.message}")
            notify(request, getString(R.string.voice_note_failed))
            return
        }

        val prefs = AppPreferences(applicationContext)
        val reader = ClientAudioReader(
            input = ByteArrayInputStream(pcm),
            format = PcmFormat(),
            loadVad = { VadFilter.load(this) },
        )
        val transcript = TranscriptAccumulator()
        repository.transcribe(
            audio = reader.read(),
            postprocessingEnabled = prefs.postprocessingEnabled.first(),
            formatNumbersAsDigits = prefs.formatNumbersAsDigits.first(),
            replacements = TextReplacements(prefs.textReplacements.first()),
        ).collect { item ->
            when (item) {
                is TranscriptResult.Partial -> transcript.onPartial(item.text)
                is TranscriptResult.Final -> transcript.onFinal(item.text, item.confidence)
                is TranscriptResult.WindowTrimmed -> transcript.onWindowTrimmed()
                is TranscriptResult.Failure, TranscriptResult.NoSpeech -> Unit
            }
        }
        val text = transcript.text
        Log.d(TAG, "Transcribed a voice note: ${text.length} characters")
        notify(request, text.ifBlank { getString(R.string.voice_note_no_speech) })
    }

    /** The voice note's file, waiting for the download; `null` (logged) when it can't be told apart. */
    private suspend fun findFile(request: Request): VoiceNoteFile? {
        repeat(FIND_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(FIND_INTERVAL_MS)
            val files = withContext(Dispatchers.IO) { candidateFiles(request) }
            when (val match = matchVoiceNote(request.message, files, store.doneFiles())) {
                is FileMatch.Found -> return match.file
                FileMatch.Ambiguous -> {
                    Log.i(TAG, "Skipped a voice note: ${files.size} files fit")
                    return null
                }
                FileMatch.NotYet -> Unit
            }
        }
        Log.i(TAG, "Skipped a voice note: no file appeared")
        return null
    }

    /** The voice notes written around [request]'s message, with their lengths. */
    private fun candidateFiles(request: Request): List<VoiceNoteFile> {
        val time = request.message.timeMs
        // Notes recorded around a week boundary land in two folders ("202647", "202648").
        val weeks = voiceNotesFolder(request.packageName).listFiles { f -> f.isDirectory }
            ?.sortedByDescending { it.name }?.take(2) ?: return emptyList()
        return weeks.flatMap { it.listFiles()?.toList() ?: emptyList() }
            .filter {
                it.isFile && it.name.endsWith(".opus") &&
                        it.lastModified() in (time - FILE_EARLY_TOLERANCE_MS)..(time + FILE_LATE_TOLERANCE_MS)
            }
            .map { VoiceNoteFile(it.path, it.lastModified(), AudioFileDecoder.durationMs(it.path)) }
    }

    private fun voiceNotesFolder(packageName: String): File {
        val app = if (packageName == "com.whatsapp.w4b") "WhatsApp Business" else "WhatsApp"
        @Suppress("DEPRECATION") // All-files access reads the real path; MediaStore can't see these files.
        val root = Environment.getExternalStorageDirectory()
        return File(root, "Android/media/$packageName/$app/Media/$app Voice Notes")
    }

    private fun notify(request: Request, text: String) {
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(request.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(request.contentIntent)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(request.message.timeMs.hashCode(), notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Posting the transcription failed", e)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.voice_note_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = getString(R.string.voice_note_channel_desc) }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
}
