package org.schabi.parakeetype.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.IOException

private const val DEQUEUE_TIMEOUT_US = 10_000L

/**
 * Decodes a compressed audio file (a WhatsApp voice note is Ogg/Opus) with the platform's
 * [MediaCodec] decoders.
 */
object AudioFileDecoder {

    /** The length of the first audio track in [path], or `null` when the container has none. */
    fun durationMs(path: String): Long? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            val format = audioTrack(extractor)?.let { extractor.getTrackFormat(it) } ?: return null
            if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else null
        } catch (e: IOException) {
            null
        } finally {
            extractor.release()
        }
    }

    /**
     * Decodes the first audio track of [path] into the pipeline's format: 16-bit little-endian
     * mono PCM at [PIPELINE_SAMPLE_RATE], ready for a [ClientAudioReader] with the default
     * [PcmFormat].
     *
     * @throws IOException if the file can't be read or has no decodable audio track.
     */
    fun decodeToPipelinePcm(path: String): ByteArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)
            val track = audioTrack(extractor) ?: throw IOException("no audio track")
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: throw IOException("no mime type")
            codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: IllegalArgumentException) {
                throw IOException("no decoder for $mime", e)
            }
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val samples = mutableListOf<ShortArray>()
            var converter: PcmConverter? = null
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index) ?: throw IOException("no input buffer")
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    converter = PcmConverter(pcmFormat(codec.outputFormat))
                } else if (index >= 0) {
                    val buffer = codec.getOutputBuffer(index) ?: throw IOException("no output buffer")
                    val bytes = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(bytes)
                    codec.releaseOutputBuffer(index, false)
                    val active = converter ?: PcmConverter(pcmFormat(codec.outputFormat)).also { converter = it }
                    samples += active.convert(bytes)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            converter?.let { samples += it.finish() }

            val all = ShortArray(samples.sumOf { it.size })
            var offset = 0
            for (part in samples) {
                part.copyInto(all, offset)
                offset += part.size
            }
            return all.toPcm16Bytes()
        } catch (e: IllegalStateException) {
            // MediaCodec.CodecException and other codec state errors.
            throw IOException("decoding failed", e)
        } finally {
            codec?.release()
            extractor.release()
        }
    }

    private fun audioTrack(extractor: MediaExtractor): Int? =
        (0 until extractor.trackCount).firstOrNull { track ->
            extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }

    private fun pcmFormat(format: MediaFormat): PcmFormat {
        val pcm = PcmFormat(
            encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            },
            channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
        )
        if (!pcm.isSupported) throw IOException("unsupported decoder output: $pcm")
        return pcm
    }
}
