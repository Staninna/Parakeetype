package org.schabi.parakeetype.inference

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class EngineThreadsTest {

    @Test
    fun `pixel 10 layout uses four encoder threads`() {
        // 2x A520 (2.0 GHz), 5x A725 (3.4 GHz), 1x X4 (3.78 GHz)
        val freqs = listOf(2_000_000L, 2_000_000L) + List(5) { 3_400_000L } + 3_780_000L
        val performance = EngineThreads.performanceCoresFromFrequencies(freqs, 8)
        assertThat(performance).isEqualTo(6)
        assertThat(EngineThreads.encoderThreadsFor(performance)).isEqualTo(4)
    }

    @Test
    fun `small phones keep at least two threads`() {
        // 4x little + 2x big
        val freqs = List(4) { 1_800_000L } + List(2) { 2_400_000L }
        assertThat(EngineThreads.encoderThreadsFor(EngineThreads.performanceCoresFromFrequencies(freqs, 6))).isEqualTo(2)
        assertThat(EngineThreads.encoderThreadsFor(1)).isEqualTo(2)
    }

    @Test
    fun `homogeneous or unreadable cpus`() {
        assertThat(EngineThreads.performanceCoresFromFrequencies(List(8) { 2_000_000L }, 8)).isEqualTo(7)
        assertThat(EngineThreads.performanceCoresFromFrequencies(emptyList(), 8)).isEqualTo(4)
    }
}
