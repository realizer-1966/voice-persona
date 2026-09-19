package com.voicepersona.app

import com.voicepersona.asr.AsrBridge
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Serialized wrapper around the native STT engine. The per-call audio ceiling
 * belongs to the model (Moonshine ~48 s, Qwen3-ASR ~87 min), so the chunk size
 * is set from the loaded [ModelSpec] rather than hard coded.
 */
class SpeechToText {
    private val lock = Mutex()
    private var handle: Long = 0L

    /** Usable seconds per native call, from the model's input contract. */
    @Volatile
    private var chunkSeconds: Int = 40

    companion object {
        const val SAMPLE_RATE = 16000
        /**
         * Chunk below the single-call limit. The limit is the point where the
         * model starts cutting its own transcript, so a small margin is enough;
         * splitting also keeps each chunk's transcript complete.
         */
        private const val CHUNK_MARGIN = 0.85
    }

    suspend fun load(model: File, nThreads: Int, maxAudioSeconds: Int): String = lock.withLock {
        withContext(Dispatchers.Default) {
            chunkSeconds = ((maxAudioSeconds * CHUNK_MARGIN).toInt()).coerceAtLeast(1)
            if (handle == 0L) handle = AsrBridge.nativeCreate()
            val info = AsrBridge.nativeLoad(handle, model.absolutePath, nThreads)
            if (info.startsWith("ERR:")) throw IllegalStateException(info.removePrefix("ERR: "))
            info
        }
    }

    suspend fun isLoaded(): Boolean = lock.withLock { handle != 0L && AsrBridge.nativeIsLoaded(handle) }

    /** True when the last native run ran out of generation budget. */
    suspend fun wasTruncated(): Boolean = lock.withLock {
        handle != 0L && AsrBridge.nativeWasTruncated(handle)
    }

    /** Longest audio this session can take in one call, in seconds. */
    suspend fun maxAudioSeconds(): Int = lock.withLock {
        if (handle == 0L) 0 else (AsrBridge.nativeMaxAudioMs(handle) / 1000).toInt()
    }

    suspend fun loadDiarizer(model: File, nThreads: Int): Boolean = lock.withLock {
        withContext(Dispatchers.Default) {
            if (handle == 0L) return@withContext false
            val info = AsrBridge.nativeLoadDiarizer(handle, model.absolutePath, nThreads)
            !info.startsWith("ERR:")
        }
    }

    /** Speaker spans as (startMs, endMs, speakerId). */
    suspend fun diarize(pcm: FloatArray): List<Triple<Int, Int, Int>> = lock.withLock {
        withContext(Dispatchers.Default) {
            if (handle == 0L) return@withContext emptyList()
            val flat = AsrBridge.nativeDiarize(handle, pcm, pcm.size) ?: return@withContext emptyList()
            (0 until flat.size / 3).map { i ->
                Triple(flat[i * 3], flat[i * 3 + 1], flat[i * 3 + 2])
            }
        }
    }

    /**
     * Transcribes only the spans belonging to [speakerId], so a phone call can
     * be reduced to one person's own words.
     *
     * The spans are padded and merged before being handed to the model: cutting
     * exactly on a boundary clips the first phoneme and costs accuracy.
     */
    suspend fun transcribeSpeaker(
        pcm: FloatArray,
        spans: List<Triple<Int, Int, Int>>,
        speakerId: Int,
    ): String {
        val mine = spans.filter { it.third == speakerId }.sortedBy { it.first }
        if (mine.isEmpty()) return ""

        val padMs = 120
        val gapMs = 400
        val merged = ArrayList<Pair<Int, Int>>()
        for ((start, end, _) in mine) {
            val s = ((start - padMs).coerceAtLeast(0)) * SAMPLE_RATE / 1000
            val e = ((end + padMs) * SAMPLE_RATE / 1000).coerceAtMost(pcm.size)
            if (s >= e) continue
            val last = merged.lastOrNull()
            if (last != null && s - last.second <= gapMs * SAMPLE_RATE / 1000) {
                merged[merged.size - 1] = last.first to e
            } else {
                merged.add(s to e)
            }
        }

        val pieces = ArrayList<FloatArray>(merged.size)
        var cursor = 0
        for ((s, e) in merged) {
            if (s > cursor) pieces.add(silence(s - cursor))
            pieces.add(pcm.copyOfRange(s, e))
            cursor = e
        }
        val joined = FloatArray(pieces.sumOf { it.size })
        var at = 0
        for (piece in pieces) {
            System.arraycopy(piece, 0, joined, at, piece.size)
            at += piece.size
        }
        return transcribe(joined)
    }

    private fun silence(samples: Int): FloatArray {
        // 0.25 s of silence is enough to stop the model gluing two turns.
        val quiet = FloatArray(samples.coerceAtMost(SAMPLE_RATE / 4))
        val total = FloatArray(samples)
        System.arraycopy(quiet, 0, total, 0, quiet.size)
        return total
    }

    suspend fun unload() = lock.withLock {
        if (handle != 0L) {
            AsrBridge.nativeUnload(handle)
            handle = 0L
        }
    }

    /** [pcm] must be 16 kHz mono float32. Long input is transcribed in chunks. */
    suspend fun transcribe(pcm: FloatArray): String = lock.withLock {
        withContext(Dispatchers.Default) {
            if (handle == 0L) throw IllegalStateException("음성 모델이 로드되지 않았습니다")
            val chunkSize = chunkSeconds * SAMPLE_RATE
            if (pcm.size <= chunkSize) {
                return@withContext runChunkComplete(pcm)
            }
            val parts = ArrayList<String>()
            var start = 0
            while (start < pcm.size) {
                val end = minOf(start + chunkSize, pcm.size)
                if (end - start < SAMPLE_RATE / 2) break   // ignore sub-0.5 s tail
                val piece = runChunkComplete(pcm.copyOfRange(start, end))
                if (piece.isNotBlank()) parts.add(piece.trim())
                start = end
            }
            parts.joinToString(" ")
        }
    }

    private fun runChunk(pcm: FloatArray): String {
        val text = AsrBridge.nativeTranscribe(handle, pcm, pcm.size)
        if (text.startsWith("ERR:")) throw IllegalStateException(text.removePrefix("ERR: "))
        return text
    }

    /**
     * Transcribes one chunk, and if the model reports that it ran out of
     * generation budget the chunk is halved and retried. A truncated run
     * returns an empty string (the library discards partial output), so the
     * retry below is what actually recovers the text — the reported limit is
     * advisory per family, so this self-corrects instead of trusting the table.
     */
    private fun runChunkComplete(pcm: FloatArray, depth: Int = 0): String {
        val text = runChunk(pcm)
        val truncated = AsrBridge.nativeWasTruncated(handle)
        if (!truncated) return text
        // Guard against runaway recursion, and stop splitting when a chunk is
        // already too small to carry meaning.
        if (depth >= 4 || pcm.size < SAMPLE_RATE * 20) return text
        val half = pcm.size / 2
        val first = runChunkComplete(pcm.copyOfRange(0, half), depth + 1)
        val second = runChunkComplete(pcm.copyOfRange(half, pcm.size), depth + 1)
        return (first.trim() + " " + second.trim()).trim()
    }

    /** Frees the native handle. Call from the owner's teardown. */
    fun release() {
        if (handle != 0L) {
            AsrBridge.nativeDestroy(handle)
            handle = 0L
        }
    }
}
