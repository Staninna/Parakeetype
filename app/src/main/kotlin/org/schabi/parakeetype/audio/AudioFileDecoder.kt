package org.schabi.parakeetype.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import org.schabi.parakeetype.notes.DecodedAudio
import java.io.IOException
import java.nio.ByteOrder

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
     * Decodes the first audio track of [path] to mono floats at the decoder's own sample rate
     * (48 kHz for Opus); resampling is left to the caller.
     *
     * @throws IOException if the file can't be read or has no decodable audio track.
     */
    fun decode(path: String): DecodedAudio {
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

            val out = FloatList()
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
                if (index >= 0) {
                    val buffer = codec.getOutputBuffer(index) ?: throw IOException("no output buffer")
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    appendMono(buffer.order(ByteOrder.LITTLE_ENDIAN), codec.outputFormat, out)
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            return DecodedAudio(out.toArray(), codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE))
        } catch (e: IllegalStateException) {
            // MediaCodec.CodecException and other codec state errors.
            throw IOException("decoding failed", e)
        } finally {
            codec?.release()
            extractor.release()
        }
    }

    /** Appends the interleaved frames in [buffer] to [out], channels averaged. */
    private fun appendMono(buffer: java.nio.ByteBuffer, format: MediaFormat, out: FloatList) {
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
        when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> {
                val shorts = buffer.asShortBuffer()
                while (shorts.remaining() >= channels) {
                    var sum = 0f
                    repeat(channels) { sum += shorts.get() / 32_768f }
                    out.add(sum / channels)
                }
            }
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = buffer.asFloatBuffer()
                while (floats.remaining() >= channels) {
                    var sum = 0f
                    repeat(channels) { sum += floats.get() }
                    out.add(sum / channels)
                }
            }
            else -> throw IOException("unsupported decoder output encoding $encoding")
        }
    }

    private fun audioTrack(extractor: MediaExtractor): Int? =
        (0 until extractor.trackCount).firstOrNull { track ->
            extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }

    /** A growable float array; a voice note has millions of samples, too many to box. */
    private class FloatList {
        private var data = FloatArray(1 shl 16)
        private var size = 0
        fun add(value: Float) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }
        fun toArray(): FloatArray = data.copyOf(size)
    }
}
