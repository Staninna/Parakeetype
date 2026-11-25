package org.schabi.parakeetype.notes

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/** The model's sample rate. */
internal const val MODEL_RATE = 16_000

/** Mono audio as floats in [-1, 1] at [sampleRate], as decoded from a voice note file. */
class DecodedAudio(val samples: FloatArray, val sampleRate: Int)

/**
 * Prepares a whole recording for the speech model. Live dictation streams a close-up phone
 * mic through a speech filter; a voice note is a finished file that may come from a poor mic,
 * so it gets a proper resampler, a rumble filter and a level correction instead, and is cut
 * into long pieces at pauses (see [segments]).
 */
internal object VoiceNoteAudio {

    /** Zero crossings of the resampling kernel on each side. More = sharper anti-aliasing. */
    private const val ZERO_CROSSINGS = 16

    /** The resampler's pass band, as a fraction of the lower Nyquist frequency. */
    private const val PASS_BAND = 0.92

    /** Wind, handling noise and mains hum sit below this; speech doesn't. */
    private const val HIGH_PASS_HZ = 80.0

    /** Speech level the model gets: the loud 5 % of 20 ms frames at -20 dBFS. */
    private const val TARGET_SPEECH_RMS = 0.1f
    private const val MAX_GAIN = 31.6f   // +30 dB: a whisper-quiet note
    private const val MIN_GAIN = 0.25f   // -12 dB: a clipped, too-loud note
    private const val LEVEL_FRAME = MODEL_RATE / 50
    private const val LEVEL_PERCENTILE = 0.95

    /** Pieces stay below this length; past it the model loses accuracy and memory grows. */
    const val MAX_SEGMENT_SAMPLES = MODEL_RATE * 25
    /** A piece is at least this long, so a cut never leaves a fragment without context. */
    const val MIN_SEGMENT_SAMPLES = MODEL_RATE * 15
    private const val PAUSE_WINDOW = MODEL_RATE / 5
    private const val PAUSE_STEP = MODEL_RATE / 100

    fun prepare(audio: DecodedAudio, highPass: Boolean = true, normalize: Boolean = true): FloatArray {
        var samples = resample(audio.samples, audio.sampleRate, MODEL_RATE)
        if (highPass) samples = highPass(samples, MODEL_RATE)
        if (normalize) samples = normalizeLevel(samples)
        return samples
    }

    /**
     * Band-limited resampling (polyphase windowed sinc, Blackman window). Downsampling
     * 48 kHz voice notes by averaging lets everything above 8 kHz fold back into the speech
     * band as noise; this filters it out first.
     */
    fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || input.isEmpty()) return input.copyOf()
        val g = gcd(from, to)
        val up = to / g        // phases
        val down = from / g
        val cutoff = 0.5 * PASS_BAND * minOf(1.0, to.toDouble() / from) // cycles per input sample
        val half = ceil(ZERO_CROSSINGS / (2 * cutoff)).toInt()
        val taps = 2 * half
        val kernels = Array(up) { phase ->
            val frac = phase.toDouble() / up
            DoubleArray(taps) { j ->
                val d = frac - (j - half + 1)
                2 * cutoff * sinc(2 * cutoff * d) * blackman(d / half)
            }
        }
        val outSize = (input.size.toLong() * up / down).toInt()
        val out = FloatArray(outSize)
        for (n in 0 until outSize) {
            val position = n.toLong() * down
            val center = (position / up).toInt()
            val kernel = kernels[(position % up).toInt()]
            val first = center - half + 1
            var acc = 0.0
            val jStart = maxOf(0, -first)
            val jEnd = minOf(taps, input.size - first)
            for (j in jStart until jEnd) acc += input[first + j] * kernel[j]
            out[n] = acc.toFloat()
        }
        return out
    }

    /** 2nd-order Butterworth high-pass at [HIGH_PASS_HZ]. */
    fun highPass(input: FloatArray, rate: Int): FloatArray {
        val k = tan(PI * HIGH_PASS_HZ / rate)
        val q = 1 / sqrt(2.0)
        val norm = 1 / (1 + k / q + k * k)
        val b0 = norm
        val b1 = -2 * norm
        val b2 = norm
        val a1 = 2 * (k * k - 1) * norm
        val a2 = (1 - k / q + k * k) * norm
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        return FloatArray(input.size) { i ->
            val x = input[i].toDouble()
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            y.toFloat()
        }
    }

    /**
     * Brings the speech to a steady level: a quiet note is raised (up to +30 dB), a loud one
     * lowered; peaks past 0.9 are softly limited instead of clipped.
     */
    fun normalizeLevel(input: FloatArray): FloatArray {
        val frames = input.size / LEVEL_FRAME
        if (frames == 0) return input.copyOf()
        val levels = FloatArray(frames) { f ->
            var sum = 0.0
            for (i in f * LEVEL_FRAME until (f + 1) * LEVEL_FRAME) sum += input[i] * input[i]
            sqrt(sum / LEVEL_FRAME).toFloat()
        }.sortedArray()
        val speech = levels[((frames - 1) * LEVEL_PERCENTILE).toInt()]
        if (speech < 1e-5f) return input.copyOf()
        val gain = (TARGET_SPEECH_RMS / speech).coerceIn(MIN_GAIN, MAX_GAIN)
        return FloatArray(input.size) { i -> softLimit(input[i] * gain) }
    }

    /**
     * Splits [samples] into pieces of at most [MAX_SEGMENT_SAMPLES], each cut at the quietest
     * 200 ms between [MIN_SEGMENT_SAMPLES] and the maximum, so no word is cut in half.
     */
    fun segments(samples: FloatArray): List<IntRange> {
        val energy = DoubleArray(samples.size + 1)
        for (i in samples.indices) energy[i + 1] = energy[i] + samples[i] * samples[i]
        val pieces = mutableListOf<IntRange>()
        var start = 0
        while (samples.size - start > MAX_SEGMENT_SAMPLES) {
            var cut = start + MAX_SEGMENT_SAMPLES
            var quietest = Double.MAX_VALUE
            var w = start + MIN_SEGMENT_SAMPLES
            while (w + PAUSE_WINDOW <= start + MAX_SEGMENT_SAMPLES) {
                val e = energy[w + PAUSE_WINDOW] - energy[w]
                if (e < quietest) {
                    quietest = e
                    cut = w + PAUSE_WINDOW / 2
                }
                w += PAUSE_STEP
            }
            pieces += start until cut
            start = cut
        }
        if (start < samples.size) pieces += start until samples.size
        return pieces
    }

    private fun softLimit(x: Float): Float {
        val a = abs(x)
        return if (a <= 0.9f) x else sign(x) * (0.9f + 0.1f * tanh((a - 0.9f) / 0.1f))
    }

    private fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

    /** Blackman window over [-1, 1]. */
    private fun blackman(u: Double): Double =
        if (abs(u) >= 1) 0.0 else 0.42 + 0.5 * cos(PI * u) + 0.08 * cos(2 * PI * u)

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}
