package org.schabi.parakeetype.inference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test
import org.schabi.parakeetype.audio.AudioChunk

/**
 * A voice note ([org.schabi.parakeetype.notes.VoiceNoteListenerService]) can be transcribed
 * while the keyboard dictates. Both run [InferenceRepository.transcribe] on the same
 * repository and engine at the same time; neither may change the other's transcript.
 */
class ConcurrentTranscribeTest {

    private fun audio(seconds: Int) = flow { repeat(seconds * 100) { emit(AudioChunk(ShortArray(160))) } }

    private suspend fun finalText(repo: InferenceRepository, seconds: Int): String =
        repo.transcribe(audio(seconds)).toList()
            .filterIsInstance<TranscriptResult.Final>()
            .joinToString(" ") { it.text }

    @Test
    fun `two transcriptions on one repository at once match their solo runs`() = runBlocking {
        val script = buildWordScript(words = 40, intervalMs = 400)
        val repo = InferenceRepository(FakeStreamingParakeet(script, perChunkDecodeMs = 5))

        val soloLong = finalText(repo, seconds = 16)
        val soloShort = finalText(repo, seconds = 8)

        val (long, short) = withContext(Dispatchers.Default) {
            val long = async { finalText(repo, seconds = 16) }
            val short = async { finalText(repo, seconds = 8) }
            long.await() to short.await()
        }

        assertEquals(soloLong, long)
        assertEquals(soloShort, short)
    }
}
