package org.schabi.parakeetype.notes

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test
import org.schabi.parakeetype.audio.ClientAudioReader
import org.schabi.parakeetype.audio.PcmConverter
import org.schabi.parakeetype.audio.PcmFormat
import org.schabi.parakeetype.audio.SileroVadFilter
import org.schabi.parakeetype.audio.toPcm16Bytes
import org.schabi.parakeetype.inference.InferenceRepository
import org.schabi.parakeetype.inference.ParakeetEngine
import org.schabi.parakeetype.inference.TranscriptResult
import org.schabi.parakeetype.inference.resolveModelDir
import org.schabi.parakeetype.recognition.TranscriptAccumulator
import org.schabi.parakeetype.settings.model.SILERO_VAD_FILE
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Compares the voice note pipelines on bad-mic recordings: word error rate per recording
 * condition. Not a pass/fail test; it prints a table.
 *
 * Needs the model (devtools/fetch-test-model.sh) and a bench set made by
 * `devtools/voice-note-bench.sh`, which also runs this test; skipped otherwise.
 */
class VoiceNoteBenchTest {

    @Test
    fun `compare voice note pipelines`() = runBlocking {
        val dir = System.getenv("PARAKEETYPE_VOICE_BENCH_DIR")?.let(::File)
        Assume.assumeTrue(dir != null && File(dir, "refs.tsv").isFile)
        val modelDir = try { resolveModelDir() } catch (e: IllegalStateException) { null }
        Assume.assumeTrue(modelDir != null)

        val engine = ParakeetEngine().apply { load(modelDir!!) }
        val vadBytes = File(modelDir, SILERO_VAD_FILE).readBytes()
        val repository = InferenceRepository(engine)
        val pipelines: List<Pair<String, suspend (ShortArray) -> String>> = listOf(
            "dictation path" to { pcm -> dictationPath(repository, vadBytes, pcm) },
            "whole, no filter" to { pcm -> VoiceNoteTranscriber(engine).transcribe(prepare(pcm, false, false)) },
            "whole + level" to { pcm -> VoiceNoteTranscriber(engine).transcribe(prepare(pcm, true, true)) },
        )

        val refs = File(dir, "refs.tsv").readLines().filter { it.isNotBlank() }.map { it.split('\t') }
        // condition -> pipeline -> (errors, words)
        val totals = sortedMapOf<String, MutableMap<String, IntArray>>()
        for ((file, reference) in refs) {
            val pcm = readPcm48k(File(dir, file))
            val condition = file.substringBefore('_')
            for ((name, run) in pipelines) {
                val hypothesis = run(pcm)
                val errors = wordErrors(reference, hypothesis)
                val words = words(reference).size
                totals.getOrPut(condition) { linkedMapOf() }.getOrPut(name) { IntArray(2) }.let {
                    it[0] += errors; it[1] += words
                }
                println("[$name] $file: ${pct(errors, words)}  | $hypothesis")
            }
        }

        val names = pipelines.map { it.first }
        println()
        println("condition".padEnd(14) + names.joinToString("") { it.padStart(20) })
        val all = names.associateWith { IntArray(2) }
        for ((condition, byPipeline) in totals) {
            println(condition.padEnd(14) + names.joinToString("") { n ->
                val t = byPipeline.getValue(n)
                all.getValue(n)[0] += t[0]; all.getValue(n)[1] += t[1]
                pct(t[0], t[1]).padStart(20)
            })
        }
        println("ALL".padEnd(14) + names.joinToString("") { n -> pct(all.getValue(n)[0], all.getValue(n)[1]).padStart(20) })
        engine.close()
    }

    /** What the listener did before: 48 kHz averaged down, Silero speech filter, streaming. */
    private suspend fun dictationPath(repository: InferenceRepository, vadBytes: ByteArray, pcm48k: ShortArray): String {
        val converter = PcmConverter(PcmFormat(sampleRate = 48_000))
        val pcm16k = converter.convert(pcm48k.toPcm16Bytes()) + converter.finish()
        val reader = ClientAudioReader(
            input = ByteArrayInputStream(pcm16k.toPcm16Bytes()),
            format = PcmFormat(),
            loadVad = { SileroVadFilter(modelBytes = vadBytes, threshold = 0.3f) },
        )
        val transcript = TranscriptAccumulator()
        repository.transcribe(reader.read()).collect { item ->
            when (item) {
                is TranscriptResult.Partial -> transcript.onPartial(item.text)
                is TranscriptResult.Final -> transcript.onFinal(item.text, item.confidence)
                is TranscriptResult.WindowTrimmed -> transcript.onWindowTrimmed()
                else -> Unit
            }
        }
        return transcript.text
    }

    private fun prepare(pcm48k: ShortArray, highPass: Boolean, normalize: Boolean): FloatArray =
        VoiceNoteAudio.prepare(
            DecodedAudio(FloatArray(pcm48k.size) { pcm48k[it] / 32_768f }, 48_000),
            highPass = highPass,
            normalize = normalize,
        )

    private fun readPcm48k(file: File): ShortArray {
        val bytes = file.readBytes()
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return shorts
    }

    private fun words(text: String): List<String> =
        text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}' ]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** Word-level edit distance. */
    private fun wordErrors(reference: String, hypothesis: String): Int {
        val r = words(reference)
        val h = words(hypothesis)
        var previous = IntArray(h.size + 1) { it }
        for (i in 1..r.size) {
            val current = IntArray(h.size + 1)
            current[0] = i
            for (j in 1..h.size) {
                current[j] = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + if (r[i - 1] == h[j - 1]) 0 else 1,
                )
            }
            previous = current
        }
        return previous[h.size]
    }

    private fun pct(errors: Int, words: Int) =
        if (words == 0) "-" else String.format(Locale.ROOT, "%.1f%% WER", 100.0 * errors / words)
}
