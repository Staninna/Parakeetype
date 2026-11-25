package org.schabi.parakeetype.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class VoiceNoteAudioTest {

    private fun tone(hz: Double, rate: Int, seconds: Double, amplitude: Float = 0.5f) =
        FloatArray((rate * seconds).toInt()) { i -> amplitude * sin(2 * PI * hz * i / rate).toFloat() }

    /** RMS of the middle half, away from the filter's edges. */
    private fun rms(x: FloatArray): Double {
        val from = x.size / 4
        val to = x.size * 3 / 4
        var sum = 0.0
        for (i in from until to) sum += x[i] * x[i]
        return sqrt(sum / (to - from))
    }

    @Test
    fun `speech frequencies pass the 48 to 16 kHz resampler unchanged`() {
        val out = VoiceNoteAudio.resample(tone(1_000.0, 48_000, 1.0), 48_000, MODEL_RATE)

        assertEquals(MODEL_RATE, out.size)
        assertEquals(0.5 / sqrt(2.0), rms(out), 0.01)
    }

    @Test
    fun `frequencies above 8 kHz are filtered out instead of folding back as noise`() {
        // A 12 kHz tone would alias to 4 kHz, right in the speech band, if only averaged.
        val out = VoiceNoteAudio.resample(tone(12_000.0, 48_000, 1.0), 48_000, MODEL_RATE)

        assertTrue("residual ${rms(out)}", rms(out) < 0.005)
    }

    @Test
    fun `44_1 kHz and 8 kHz inputs come out at 16 kHz`() {
        assertEquals(MODEL_RATE, VoiceNoteAudio.resample(tone(500.0, 44_100, 1.0), 44_100, MODEL_RATE).size)
        val up = VoiceNoteAudio.resample(tone(500.0, 8_000, 1.0), 8_000, MODEL_RATE)
        assertEquals(MODEL_RATE, up.size)
        assertEquals(0.5 / sqrt(2.0), rms(up), 0.01)
    }

    @Test
    fun `rumble below speech is removed, speech is kept`() {
        val rumble = VoiceNoteAudio.highPass(tone(20.0, MODEL_RATE, 2.0), MODEL_RATE)
        val voice = VoiceNoteAudio.highPass(tone(300.0, MODEL_RATE, 2.0), MODEL_RATE)

        assertTrue(rms(rumble) < 0.03)
        assertEquals(0.5 / sqrt(2.0), rms(voice), 0.02)
    }

    @Test
    fun `a quiet recording is raised to speech level`() {
        val out = VoiceNoteAudio.normalizeLevel(tone(300.0, MODEL_RATE, 2.0, amplitude = 0.005f))

        assertEquals(0.1, rms(out), 0.01)
    }

    @Test
    fun `a short note stays one piece`() {
        val pieces = VoiceNoteAudio.segments(FloatArray(MODEL_RATE * 20))

        assertEquals(listOf(0 until MODEL_RATE * 20), pieces)
    }

    @Test
    fun `a long note is cut in its pause`() {
        // 60 s of "speech" with a 300 ms pause at 20 s and at 42 s.
        val audio = tone(300.0, MODEL_RATE, 60.0)
        for (pause in listOf(20.0, 42.0)) {
            val start = (pause * MODEL_RATE).toInt()
            for (i in start until start + MODEL_RATE * 3 / 10) audio[i] = 0f
        }

        val pieces = VoiceNoteAudio.segments(audio)

        assertEquals(3, pieces.size)
        assertEquals(0, pieces.first().first)
        assertEquals(audio.size - 1, pieces.last().last)
        val cuts = pieces.drop(1).map { it.first / MODEL_RATE.toDouble() }
        assertEquals(20.15, cuts[0], 0.15)
        assertEquals(42.15, cuts[1], 0.15)
        pieces.forEach { assertTrue(it.count() <= VoiceNoteAudio.MAX_SEGMENT_SAMPLES) }
    }
}
