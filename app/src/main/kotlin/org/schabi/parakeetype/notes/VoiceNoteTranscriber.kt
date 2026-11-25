package org.schabi.parakeetype.notes

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.schabi.parakeetype.audio.AudioChunk
import org.schabi.parakeetype.audio.AudioFileDecoder
import org.schabi.parakeetype.inference.SpeechEngine
import org.schabi.parakeetype.inference.TextReplacements
import org.schabi.parakeetype.inference.TranscriptResult
import org.schabi.parakeetype.inference.cleanTranscript
import org.schabi.parakeetype.inference.stripScriptHallucinations
import org.schabi.parakeetype.inference.zeroPadToMinimum
import org.schabi.parakeetype.settings.preferences.AppPreferences
import java.io.IOException

/**
 * Decodes the voice note at [path] and transcribes it with the user's text settings. Received
 * notes, the test screen ([VoiceNotesActivity]) and shared audio all go through here.
 *
 * @throws IOException when the file can't be decoded or the engine fails.
 */
internal suspend fun transcribeVoiceNoteFile(context: Context, engine: SpeechEngine, path: String): String {
    val prefs = AppPreferences(context.applicationContext)
    val postprocessing = prefs.postprocessingEnabled.first()
    val formatNumbers = prefs.formatNumbersAsDigits.first()
    val replacements = TextReplacements(prefs.textReplacements.first())
    val audio = withContext(Dispatchers.IO) { AudioFileDecoder.decode(path) }
    return withContext(Dispatchers.Default) {
        VoiceNoteTranscriber(engine).transcribe(VoiceNoteAudio.prepare(audio), postprocessing, formatNumbers, replacements)
    }
}

/** Silence before each piece: the TDT decoder misses a first word that starts at sample 0. */
private const val LEAD_SILENCE_SAMPLES = MODEL_RATE * 6 / 10

/** Shortest input the engine gets (the NeMo reference pads short clips to 1.25 s). */
private const val MIN_INPUT_SAMPLES = MODEL_RATE * 5 / 4

/**
 * Transcribes a whole voice note in one pass per piece (at most 25 s, cut at pauses), with
 * no speech filter in front: the sender meant all of it as speech, and on a poor recording
 * the dictation path's filter drops quiet words and its 2 s streaming window loses the
 * sentence context the model needs to make sense of unclear audio.
 */
internal class VoiceNoteTranscriber(private val engine: SpeechEngine) {

    /**
     * @param audio Prepared 16 kHz audio ([VoiceNoteAudio.prepare]).
     * @return The cleaned transcript; blank when no speech was found.
     * @throws IOException when the engine fails.
     */
    fun transcribe(
        audio: FloatArray,
        postprocessing: Boolean = true,
        formatNumbersAsDigits: Boolean = true,
        replacements: TextReplacements = TextReplacements.NONE,
    ): String {
        val raw = VoiceNoteAudio.segments(audio).mapNotNull { piece ->
            when (val result = engine.transcribe(AudioChunk(toModelInput(audio, piece)))) {
                is TranscriptResult.Final -> result.text
                is TranscriptResult.Partial -> result.text
                is TranscriptResult.Failure -> throw IOException("transcription failed", result.cause)
                else -> null
            }?.trim()?.takeIf { it.isNotEmpty() }
        }.joinToString(" ")
        if (!postprocessing || raw.isBlank()) return raw
        val language = engine.currentLanguage
        val cleaned = raw.cleanTranscript(
            language = language,
            formatNumbersAsDigits = formatNumbersAsDigits,
            replacements = replacements,
        )
        return stripScriptHallucinations(cleaned, language) ?: ""
    }

    private fun toModelInput(audio: FloatArray, piece: IntRange): ShortArray {
        val pcm = ShortArray(LEAD_SILENCE_SAMPLES + piece.count())
        for ((i, s) in piece.withIndex()) {
            pcm[LEAD_SILENCE_SAMPLES + i] = (audio[s].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        return zeroPadToMinimum(pcm, MIN_INPUT_SAMPLES)
    }
}
