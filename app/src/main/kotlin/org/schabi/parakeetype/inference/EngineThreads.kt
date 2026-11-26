package org.schabi.parakeetype.inference

import java.io.File

/**
 * Upper bound for the encoder's intra-op threads. Beyond four, the encoder's matrix products no
 * longer get faster on phone CPUs, and the remaining cores stay free for audio capture and UI.
 */
private const val MAX_ENCODER_THREADS = 4

/** Thread counts for the ONNX sessions, derived from the phone's CPU layout. */
object EngineThreads {

    /**
     * Intra-op threads for the encoder (and preprocessor): the performance cores, at most
     * [MAX_ENCODER_THREADS], at least 2. On a Pixel 10 (1x X4, 5x A725, 2x A520) this gives 4.
     */
    fun encoderThreads(): Int = encoderThreadsFor(performanceCoreCount())

    internal fun encoderThreadsFor(performanceCores: Int): Int =
        performanceCores.coerceIn(2, MAX_ENCODER_THREADS)

    /**
     * Number of "performance" cores: all cores except the slowest cluster, detected from each
     * core's maximum frequency. Falls back to half the cores if cpufreq is not readable.
     */
    private fun performanceCoreCount(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        val freqs = (0 until cores).mapNotNull { cpu ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            }.getOrNull()
        }
        return performanceCoresFromFrequencies(freqs, cores)
    }

    internal fun performanceCoresFromFrequencies(maxFreqs: List<Long>, totalCores: Int): Int {
        if (maxFreqs.isEmpty()) return (totalCores / 2).coerceAtLeast(1)
        val slowest = maxFreqs.min()
        val fast = maxFreqs.count { it > slowest }
        // Homogeneous CPU: all cores but one.
        return if (fast == 0) (maxFreqs.size - 1).coerceAtLeast(1) else fast
    }
}
