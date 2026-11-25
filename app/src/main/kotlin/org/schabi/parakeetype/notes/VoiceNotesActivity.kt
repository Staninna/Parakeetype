package org.schabi.parakeetype.notes

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.format.DateUtils
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.schabi.parakeetype.R
import org.schabi.parakeetype.audio.AudioFileDecoder
import org.schabi.parakeetype.recognition.InferenceConnection
import org.schabi.parakeetype.settings.screens.GroupSpacer
import org.schabi.parakeetype.settings.screens.ItemSummary
import org.schabi.parakeetype.settings.screens.ItemTitle
import org.schabi.parakeetype.settings.screens.PageText
import org.schabi.parakeetype.settings.screens.SectionHeader
import org.schabi.parakeetype.settings.screens.SettingsCard
import org.schabi.parakeetype.settings.screens.SettingsColumn
import org.schabi.parakeetype.settings.screens.SettingsGroup
import org.schabi.parakeetype.settings.screens.SettingsItem
import org.schabi.parakeetype.ui.theme.ParakeetypeTheme
import java.io.File
import java.io.IOException

private const val TAG = "VoiceNotesScreen"

/** The screen lists this many of the newest voice notes. */
private const val MAX_NOTES = 50

/**
 * Tests voice note transcription on real audio: record a message ([VoiceNoteTestDialog]), or
 * play a voice note already in WhatsApp's folder and transcribe it the way received notes are.
 * Opened from the home screen's voice notes row, or by sharing audio to Parakeetype (any app's
 * share sheet), which transcribes the shared audio right away. Nothing is stored or sent; the
 * text lives only on this screen.
 */
class VoiceNotesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Audio shared to Parakeetype, e.g. a voice note from WhatsApp's share sheet.
        val shared = if (intent.action == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            null
        }
        setContent {
            ParakeetypeTheme { VoiceNotesScreen(shared = shared, onBack = ::finish) }
        }
    }
}

/**
 * Copies shared audio into the cache, so it can be decoded and played like a voice note file;
 * `null` when it can't be read.
 */
private fun copySharedAudio(context: Context, uri: Uri, target: File): SavedNote? = try {
    val input = context.contentResolver.openInputStream(uri) ?: throw IOException("no stream")
    input.use { from -> target.outputStream().use { from.copyTo(it) } }
    SavedNote(target.path, System.currentTimeMillis(), AudioFileDecoder.durationMs(target.path))
} catch (e: IOException) {
    Log.w(TAG, "Can't read the shared audio: ${e.message}")
    null
} catch (e: SecurityException) {
    Log.w(TAG, "No access to the shared audio: ${e.message}")
    null
}

private class SavedNote(val path: String, val modifiedMs: Long, val durationMs: Long?)

private sealed interface NoteText {
    data object Working : NoteText
    data class Done(val text: String) : NoteText
    data object Failed : NoteText
}

/** The newest voice notes of WhatsApp and WhatsApp Business, sent and received. */
private fun recentVoiceNotes(): List<SavedNote> =
    VOICE_NOTE_PACKAGES
        .flatMap { pkg -> messengerVoiceNotesFolder(pkg).listFiles { f -> f.isDirectory }.orEmpty().toList() }
        .flatMap { week -> week.listFiles().orEmpty().toList() }
        .filter { it.isFile && it.name.endsWith(".opus") }
        .sortedByDescending { it.lastModified() }
        .take(MAX_NOTES)
        .map { SavedNote(it.path, it.lastModified(), AudioFileDecoder.durationMs(it.path)) }

/** Plays one voice note at a time. */
private class NotePlayer {
    var playing by mutableStateOf<String?>(null)
        private set
    private var player: MediaPlayer? = null

    fun toggle(path: String) {
        val wasPlaying = playing
        stop()
        if (wasPlaying == path) return
        val p = MediaPlayer()
        try {
            p.setDataSource(path)
            p.prepare()
        } catch (e: IOException) {
            Log.w(TAG, "Can't play this voice note: ${e.message}")
            p.release()
            return
        }
        p.setOnCompletionListener { stop() }
        p.start()
        player = p
        playing = path
    }

    fun stop() {
        player?.release()
        player = null
        playing = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceNotesScreen(shared: Uri?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val inference = remember { InferenceConnection(context) }
    val player = remember { NotePlayer() }
    // One transcription at a time; the phone has one model and limited memory.
    val engineBusy = remember { Mutex() }
    val texts = remember { mutableStateMapOf<String, NoteText>() }
    val canRead = remember { Environment.isExternalStorageManager() }
    var notes by remember { mutableStateOf<List<SavedNote>?>(null) }
    var testing by remember { mutableStateOf(false) }
    val sharedFile = remember { File(context.cacheDir, "shared-voice-note") }
    // null while copying; a failed copy shows as a failed transcription.
    var sharedNote by remember { mutableStateOf<SavedNote?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            player.stop()
            inference.unbind()
            sharedFile.delete()
        }
    }
    if (testing) VoiceNoteTestDialog(onDismiss = { testing = false })

    fun transcribe(note: SavedNote) {
        texts[note.path] = NoteText.Working
        scope.launch {
            texts[note.path] = engineBusy.withLock {
                when (val result = inference.awaitRepository()) {
                    is InferenceConnection.Result.Unavailable -> {
                        Log.w(TAG, "Engine unavailable: ${result.reason}")
                        NoteText.Failed
                    }
                    is InferenceConnection.Result.Ready -> try {
                        NoteText.Done(transcribeVoiceNoteFile(context, result.repository.speechEngine, note.path))
                    } catch (e: IOException) {
                        Log.w(TAG, "Transcribing a voice note failed: ${e.message}")
                        NoteText.Failed
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (shared != null) {
            val note = withContext(Dispatchers.IO) { copySharedAudio(context, shared, sharedFile) }
            if (note != null) {
                sharedNote = note
                transcribe(note)
            } else {
                texts[sharedFile.path] = NoteText.Failed
            }
        }
        notes = if (canRead) withContext(Dispatchers.IO) { recentVoiceNotes() } else emptyList()
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val barColor = MaterialTheme.colorScheme.surfaceContainer
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = barColor,
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.voice_notes_screen_title)) },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = barColor, scrolledContainerColor = barColor),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            SettingsColumn {
                PageText(stringResource(R.string.voice_notes_screen_intro))
                if (shared != null) {
                    SectionHeader(stringResource(R.string.voice_notes_shared_header))
                    val note = sharedNote
                    when {
                        note != null -> SettingsGroup(
                            listOf { shape ->
                                NoteCard(
                                    note = note,
                                    text = texts[note.path],
                                    playing = player.playing == note.path,
                                    onPlay = { player.toggle(note.path) },
                                    onTranscribe = { transcribe(note) },
                                    shape = shape,
                                )
                            },
                        )
                        texts[sharedFile.path] == NoteText.Failed ->
                            PageText(stringResource(R.string.voice_notes_shared_unreadable))
                        else -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                    }
                    GroupSpacer()
                }
                SettingsGroup(
                    listOf { shape ->
                        SettingsItem(
                            title = stringResource(R.string.voice_notes_record_test),
                            subtitle = stringResource(R.string.voice_notes_record_test_subtitle),
                            icon = Icons.Rounded.Mic,
                            shape = shape,
                            onClick = { testing = true },
                        )
                    },
                )
                SectionHeader(stringResource(R.string.voice_notes_saved_header))
                val list = notes
                when {
                    !canRead -> PageText(stringResource(R.string.voice_notes_needs_access))
                    list == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                    list.isEmpty() -> PageText(stringResource(R.string.voice_notes_none))
                    else -> SettingsGroup(
                        list.map { note ->
                            { shape ->
                                NoteCard(
                                    note = note,
                                    text = texts[note.path],
                                    playing = player.playing == note.path,
                                    onPlay = { player.toggle(note.path) },
                                    onTranscribe = { transcribe(note) },
                                    shape = shape,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteCard(
    note: SavedNote,
    text: NoteText?,
    playing: Boolean,
    onPlay: () -> Unit,
    onTranscribe: () -> Unit,
    shape: Shape,
) {
    val context = LocalContext.current
    SettingsCard(shape = shape) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPlay) {
                Icon(
                    imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(if (playing) R.string.voice_notes_pause else R.string.voice_notes_play),
                )
            }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                ItemTitle(
                    DateUtils.formatDateTime(
                        context,
                        note.modifiedMs,
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or
                            DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL,
                    ),
                )
                note.durationMs?.let { ItemSummary(DateUtils.formatElapsedTime(it / 1000)) }
            }
            TextButton(onClick = onTranscribe, enabled = text != NoteText.Working) {
                Text(stringResource(R.string.voice_notes_transcribe))
            }
        }
        when (text) {
            null -> Unit
            NoteText.Working -> LinearProgressIndicator(Modifier.fillMaxWidth())
            NoteText.Failed -> ItemSummary(stringResource(R.string.voice_notes_transcribe_failed))
            is NoteText.Done -> SelectionContainer {
                Text(
                    text.text.ifBlank { stringResource(R.string.voice_note_no_speech) },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
