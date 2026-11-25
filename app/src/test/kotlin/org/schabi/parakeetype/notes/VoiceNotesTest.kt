package org.schabi.parakeetype.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Message texts follow a real WhatsApp notification (Android, English UI, Nov 2026):
 * a voice message's `android.messages` entry reads "🎤 Voice message (0:19)". Names are
 * placeholders.
 */
class VoiceNotesTest {

    private val t0 = 1_795_606_260_000L

    private fun voice(time: Long, length: String = "0:19", sender: String = "Contact A") =
        NotificationMessage("🎤 Voice message ($length)", time, sender)

    private fun text(time: Long, text: String = "See you friday") = NotificationMessage(text, time, "Contact A")

    @Test
    fun `a voice message is detected with its sender and length`() {
        val found = newVoiceMessages(listOf(text(t0 - 5_000), voice(t0)), seenUntilMs = 0)

        assertEquals(listOf(VoiceMessage("Contact A", t0, 19_000)), found)
    }

    @Test
    fun `lengths with hours parse`() {
        assertEquals(3_723_000L, parseDurationMs("🎤 Voice message (1:02:03)"))
        assertNull(parseDurationMs("🎤 Voice message"))
    }

    @Test
    fun `a re-post with a newer text message does not bring the old voice message back`() {
        // First post: the voice message arrives and is handled.
        val first = listOf(voice(t0))
        assertEquals(1, newVoiceMessages(first, seenUntilMs = 0).size)

        // WhatsApp re-posts the conversation with its history when a text follows.
        val repost = listOf(voice(t0), text(t0 + 30_000))
        assertTrue(newVoiceMessages(repost, seenUntilMs = t0).isEmpty())
    }

    @Test
    fun `messages from before the feature was switched on are ignored`() {
        assertTrue(newVoiceMessages(listOf(voice(t0)), seenUntilMs = t0 + 1).isEmpty())
    }

    @Test
    fun `a message that only mentions the microphone later is not a voice message`() {
        val found = newVoiceMessages(listOf(text(t0, "I love my 🎤")), seenUntilMs = 0)

        assertTrue(found.isEmpty())
    }

    private val message = VoiceMessage("Contact A", t0, 19_000)

    private fun file(name: String, writtenAfterMs: Long, durationMs: Long? = 19_200) =
        VoiceNoteFile("/notes/$name.opus", t0 + writtenAfterMs, durationMs)

    @Test
    fun `the one file written around the message is the match`() {
        val note = file("PTT-WA0001", writtenAfterMs = 2_000)
        val old = file("PTT-WA0000", writtenAfterMs = -3_600_000)

        assertEquals(FileMatch.Found(note), matchVoiceNote(message, listOf(old, note), done = emptySet()))
    }

    @Test
    fun `no file yet means the download is still running`() {
        assertEquals(FileMatch.NotYet, matchVoiceNote(message, emptyList(), done = emptySet()))
    }

    @Test
    fun `the length tells an incoming note from your own reply`() {
        val note = file("PTT-WA0001", writtenAfterMs = 1_000)
        val ownReply = file("PTT-WA0002", writtenAfterMs = 30_000, durationMs = 6_000)

        assertEquals(FileMatch.Found(note), matchVoiceNote(message, listOf(note, ownReply), done = emptySet()))
    }

    @Test
    fun `two files of the same length are ambiguous`() {
        val a = file("PTT-WA0001", writtenAfterMs = 1_000)
        val b = file("PTT-WA0002", writtenAfterMs = 3_000, durationMs = 18_500)

        assertEquals(FileMatch.Ambiguous, matchVoiceNote(message, listOf(a, b), done = emptySet()))
    }

    @Test
    fun `two files without a length in the notification are ambiguous`() {
        val noLength = message.copy(durationMs = null)
        val a = file("PTT-WA0001", writtenAfterMs = 1_000)
        val b = file("PTT-WA0002", writtenAfterMs = 3_000, durationMs = 6_000)

        assertEquals(FileMatch.Ambiguous, matchVoiceNote(noLength, listOf(a, b), done = emptySet()))
    }

    @Test
    fun `an already transcribed file is never picked again`() {
        val done = file("PTT-WA0001", writtenAfterMs = 1_000)

        assertEquals(FileMatch.NotYet, matchVoiceNote(message, listOf(done), done = setOf(done.path)))
    }
}
