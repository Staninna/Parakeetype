package org.schabi.parakeetype.inference

import ai.onnxruntime.OrtSession.SessionOptions.OptLevel
import org.junit.Assume
import org.junit.Test
import org.schabi.parakeetype.audio.AudioChunk
import java.io.File

/**
 * Measures how long the Parakeet engine takes per utterance for several ONNX session
 * configurations (encoder threads × graph optimisation level), on the WAV files (16 kHz mono
 * PCM16) in the folder named by `PARAKEETYPE_BENCH_AUDIO`, and prints each transcript so a
 * change in the output shows up next to its timing.
 *
 * Needs the model (devtools/fetch-test-model.sh); skipped without the model or the folder.
 */
class EngineSpeedBenchTest {

    @Test
    fun benchSessionConfigurations() {
        val audioDir = System.getenv("PARAKEETYPE_BENCH_AUDIO")?.let(::File)
        Assume.assumeTrue("PARAKEETYPE_BENCH_AUDIO not set", audioDir?.isDirectory == true)
        val modelDir = runCatching { resolveModelDir() }.getOrNull()
        Assume.assumeTrue("Parakeet model not available", modelDir != null)

        val clips = audioDir!!.listFiles { f -> f.extension == "wav" }!!.sorted().map { file ->
            file.name to file.inputStream().use { WavReader().loadAsSingleChunk(it) }
        }
        // PARAKEETYPE_BENCH_CONFIGS="4:ALL_OPT,2:BASIC_OPT" picks the configurations.
        val configs = System.getenv("PARAKEETYPE_BENCH_CONFIGS")?.split(',')?.map {
            val (threads, opt) = it.split(':')
            threads.toInt() to OptLevel.valueOf(opt)
        } ?: listOf(2 to OptLevel.BASIC_OPT, 2 to OptLevel.ALL_OPT, 4 to OptLevel.BASIC_OPT, 4 to OptLevel.ALL_OPT)
        for ((threads, opt) in configs) {
            val engine = ParakeetEngine(encoderThreads = threads, optLevel = opt)
            val loadStart = System.nanoTime()
            engine.load(modelDir!!)
            val loadMs = (System.nanoTime() - loadStart) / 1_000_000
            var total = 0L
            val lines = clips.map { (name, chunk) ->
                // As the production short-utterance path: 600 ms lead-in silence.
                val audio = AudioChunk(ShortArray(9_600) + chunk.samples)
                engine.transcribe(audio) // warm-up
                val times = (1..3).map {
                    val start = System.nanoTime()
                    engine.transcribe(audio)
                    (System.nanoTime() - start) / 1_000_000
                }.sorted()
                total += times[1]
                val text = (engine.transcribe(audio) as? TranscriptResult.Final)?.text
                "  %-16s %5d ms  %s".format(name, times[1], text)
            }
            engine.close()
            println("BENCH threads=$threads opt=$opt load=${loadMs}ms total=${total}ms")
            lines.forEach(::println)
        }
    }
}
