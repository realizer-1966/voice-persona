package com.voicepersona.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Microphone capture at 16 kHz mono, the only rate the STT models accept.
 */
class MicRecorder {
    companion object {
        const val SAMPLE_RATE = 16000
    }

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private val samples = ArrayList<FloatArray>(256)
    @Volatile var lastRms: Float = 0f
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuf, SAMPLE_RATE * 2)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("마이크를 열 수 없습니다")
        }
        samples.clear()
        record = rec
        running = true
        rec.startRecording()
        thread = Thread {
            val buf = ShortArray(bufferSize / 2)
            while (running) {
                val read = rec.read(buf, 0, buf.size)
                if (read <= 0) continue
                val chunk = FloatArray(read)
                var acc = 0.0
                for (i in 0 until read) {
                    val v = buf[i] / 32768f
                    chunk[i] = v
                    acc += (v * v).toDouble()
                }
                lastRms = sqrt(acc / read).toFloat()
                synchronized(samples) { samples.add(chunk) }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    /** Stops capture and returns everything recorded, at 16 kHz mono. */
    fun stop(): FloatArray {
        running = false
        thread?.join(1500)
        thread = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
        val all = synchronized(samples) {
            val copy = samples.toList()
            samples.clear()
            copy
        }
        val total = all.sumOf { it.size }
        val out = FloatArray(total)
        var at = 0
        for (chunk in all) {
            System.arraycopy(chunk, 0, out, at, chunk.size)
            at += chunk.size
        }
        return out
    }
}

/**
 * Decodes any audio the system can read (m4a, mp3, wav, ogg, ...) into
 * 16 kHz mono float32 by way of MediaExtractor + MediaCodec and a linear
 * resampler.
 */
object AudioDecoder {
    private const val TARGET_RATE = 16000

    fun decode(context: Context, uri: Uri): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        val audioTrack = (0 until extractor.trackCount)
            .map { index -> index to extractor.getTrackFormat(index) }
            .firstOrNull { (_, trackFormat) ->
                (trackFormat.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")
            }
        requireNotNull(audioTrack) { "오디오 트랙을 찾을 수 없습니다" }

        val trackIndex = audioTrack.first
        val format = audioTrack.second
        extractor.selectTrack(trackIndex)

        val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val inputRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)

        val pcm16 = ArrayList<ShortArray>(1024)
        val info = MediaCodec.BufferInfo()
        var sawInputEnd = false
        var sawOutputEnd = false
        var outChannels = channels
        var outRate = inputRate

        while (!sawOutputEnd) {
            if (!sawInputEnd) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inBuf = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(inBuf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEnd = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val nf = codec.outputFormat
                    outRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    outChannels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
                outIndex >= 0 -> {
                    val outBuf = codec.getOutputBuffer(outIndex)
                    if (outBuf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val shorts = ByteBuffer.allocate(info.size)
                            .order(ByteOrder.nativeOrder())
                            .put(outBuf)
                            .array()
                        val shortBuf = ShortArray(info.size / 2)
                        ByteBuffer.wrap(shorts).order(ByteOrder.nativeOrder()).asShortBuffer()
                            .get(shortBuf)
                        synchronized(pcm16) { pcm16.add(shortBuf) }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEnd = true
                }
            }
        }
        codec.stop()
        codec.release()
        extractor.release()

        val mono = downmix(synchronized(pcm16) { pcm16.toList() }, outChannels)
        return resample(mono, outRate, TARGET_RATE)
    }

    private fun downmix(chunks: List<ShortArray>, channels: Int): FloatArray {
        if (channels <= 1) {
            val total = chunks.sumOf { it.size }
            val out = FloatArray(total)
            var at = 0
            for (c in chunks) {
                for (v in c) out[at++] = v / 32768f
            }
            return out
        }
        var frames = 0
        for (c in chunks) frames += c.size / channels
        val out = FloatArray(frames)
        var frame = 0
        for (c in chunks) {
            var i = 0
            while (i + channels <= c.size) {
                var acc = 0f
                for (k in 0 until channels) acc += c[i + k] / 32768f
                out[frame++] = acc / channels
                i += channels
            }
        }
        return out
    }

    private fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || input.isEmpty()) return input
        val ratio = from.toDouble() / to.toDouble()
        val outLen = (input.size / ratio).toInt()
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val src = i * ratio
            val i0 = src.toInt().coerceIn(0, input.size - 1)
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val frac = (src - i0).toFloat()
            out[i] = input[i0] * (1 - frac) + input[i1] * frac
        }
        return out
    }

    /** Reads a plain 16-bit PCM WAV the app itself recorded/exported. */
    fun decodeWav(file: File): FloatArray {
        val bytes = file.readBytes()
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = le32(bytes, pos + 4)
            if (id == "data") {
                val n = size / 2
                val out = FloatArray(n)
                for (i in 0 until n) {
                    val lo = bytes[pos + 8 + i * 2].toInt() and 0xFF
                    val hi = bytes[pos + 8 + i * 2 + 1].toInt()
                    val v = ((hi shl 8) or lo).toShort().toInt()
                    out[i] = v / 32768f
                }
                return out
            }
            pos += 8 + size
        }
        return FloatArray(0)
    }

    private fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
}
