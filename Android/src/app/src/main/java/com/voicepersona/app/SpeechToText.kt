package com.voicepersona.app

import com.voicepersona.asr.AsrBridge
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Serialized wrapper around the native STT engine. Moonshine handles up to
 * ~48 s per run, so longer audio is split into chunks.
 */
class SpeechToText {
    private val lock = Mutex()
    private var handle: Long = 0L

    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHUNK_SECONDS = 40
    }

    suspend fun load(model: File, nThreads: Int): String = lock.withLock {
        withContext(Dispatchers.Default) {
            if (handle == 0L) handle = AsrBridge.nativeCreate()
            val info = AsrBridge.nativeLoad(handle, model.absolutePath, nThreads)
            if (info.startsWith("ERR:")) throw IllegalStateException(info.removePrefix("ERR: "))
            info
        }
    }

    suspend fun isLoaded(): Boolean = lock.withLock { handle != 0L && AsrBridge.nativeIsLoaded(handle) }

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
            val chunkSize = CHUNK_SECONDS * SAMPLE_RATE
            if (pcm.size <= chunkSize) {
                return@withContext runChunk(pcm)
            }
            val parts = ArrayList<String>()
            var start = 0
            while (start < pcm.size) {
                val end = minOf(start + chunkSize, pcm.size)
                if (end - start < SAMPLE_RATE / 2) break   // ignore sub-0.5 s tail
                val piece = runChunk(pcm.copyOfRange(start, end))
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

    override fun finalize() {
        if (handle != 0L) {
            AsrBridge.nativeDestroy(handle)
            handle = 0L
        }
    }
}
