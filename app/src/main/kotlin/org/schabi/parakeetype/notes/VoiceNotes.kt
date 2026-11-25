package org.schabi.parakeetype.notes

import kotlin.math.abs

/** The messengers whose voice notes are transcribed: WhatsApp and WhatsApp Business. */
val VOICE_NOTE_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

/** WhatsApp starts the preview of a voice message with this emoji ("🎤 Voice message (0:19)"). */
private const val MICROPHONE = "🎤"

/** The voice message's length at the end of its preview: "(0:19)" or "(1:02:03)". */
private val DURATION = Regex("""\((?:(\d+):)?(\d+):(\d{2})\)\s*$""")

/** How far a voice note's file may be written before its message's timestamp. */
internal const val FILE_EARLY_TOLERANCE_MS = 10_000L

/** How far after its message's timestamp a voice note's file may be written (auto-download). */
internal const val FILE_LATE_TOLERANCE_MS = 120_000L

/** How far a file's length may differ from the length in the notification. */
internal const val DURATION_TOLERANCE_MS = 1_500L

/** One message of a messaging-style notification, reduced to what the matching needs. */
data class NotificationMessage(val text: String?, val timeMs: Long, val sender: String?)

/** An incoming voice message, read from a notification. */
data class VoiceMessage(val sender: String?, val timeMs: Long, val durationMs: Long?)

/** A voice note file in the messenger's Voice Notes folder. */
data class VoiceNoteFile(val path: String, val lastModifiedMs: Long, val durationMs: Long?)

sealed class FileMatch {
    data class Found(val file: VoiceNoteFile) : FileMatch()

    /** No file fits yet: the messenger may still be downloading it. */
    data object NotYet : FileMatch()

    /** More than one file fits and nothing tells them apart. */
    data object Ambiguous : FileMatch()
}

/**
 * The voice messages among [messages] that are newer than [seenUntilMs], oldest first.
 *
 * WhatsApp re-posts a conversation's notification with its whole history on every new message,
 * so a voice message already handled shows up again; [seenUntilMs] filters those out.
 */
fun newVoiceMessages(messages: List<NotificationMessage>, seenUntilMs: Long): List<VoiceMessage> =
    messages
        .filter { it.timeMs > seenUntilMs }
        .mapNotNull { message ->
            val text = message.text?.trim() ?: return@mapNotNull null
            if (!text.startsWith(MICROPHONE)) return@mapNotNull null
            VoiceMessage(message.sender, message.timeMs, parseDurationMs(text))
        }
        .sortedBy { it.timeMs }

/** The length in a voice message's preview ("(0:19)"), or `null` when it has none. */
internal fun parseDurationMs(text: String): Long? {
    val match = DURATION.find(text) ?: return null
    val (hours, minutes, seconds) = match.destructured
    return ((hours.toLongOrNull() ?: 0) * 3600 + minutes.toLong() * 60 + seconds.toLong()) * 1000
}

/**
 * Picks the file of [message] from [files]. A file fits when it isn't in [done], was written
 * around the message's time, and (when both lengths are known) is as long as the notification
 * says.
 *
 * The Voice Notes folder also holds the user's own recordings and notes from other chats, so
 * this never guesses: with more than one fitting file the result is [FileMatch.Ambiguous].
 */
fun matchVoiceNote(message: VoiceMessage, files: List<VoiceNoteFile>, done: Set<String>): FileMatch {
    val fitting = files.filter { file ->
        file.path !in done &&
                file.lastModifiedMs >= message.timeMs - FILE_EARLY_TOLERANCE_MS &&
                file.lastModifiedMs <= message.timeMs + FILE_LATE_TOLERANCE_MS &&
                (message.durationMs == null || file.durationMs == null ||
                        abs(file.durationMs - message.durationMs) <= DURATION_TOLERANCE_MS)
    }
    return when (fitting.size) {
        0 -> FileMatch.NotYet
        1 -> FileMatch.Found(fitting.single())
        else -> FileMatch.Ambiguous
    }
}
