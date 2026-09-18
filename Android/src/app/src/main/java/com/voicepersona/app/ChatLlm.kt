package com.voicepersona.app

import com.voicepersona.llm.LlamaBridge
import com.voicepersona.llm.TokenCallback
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Serialized wrapper around the native chat engine. The native side is single
 * threaded, so every call goes through [lock].
 */
class ChatLlm {
    private val lock = Mutex()
    private var handle: Long = 0L

    suspend fun load(model: File, nThreads: Int, nCtx: Int = 2048, nPredict: Int = 256): String =
        lock.withLock {
            withContext(Dispatchers.Default) {
                if (handle == 0L) handle = LlamaBridge.nativeCreate()
                val info = LlamaBridge.nativeLoad(
                    handle, model.absolutePath, nThreads, nCtx, nPredict
                )
                if (info.startsWith("ERR:")) throw IllegalStateException(info.removePrefix("ERR: "))
                info
            }
        }

    suspend fun isLoaded(): Boolean = lock.withLock { handle != 0L && LlamaBridge.nativeIsLoaded(handle) }

    suspend fun info(): String = lock.withLock { if (handle == 0L) "not loaded" else LlamaBridge.nativeInfo(handle) }

    suspend fun setSystem(prompt: String) = lock.withLock {
        if (handle != 0L) LlamaBridge.nativeSetSystem(handle, prompt)
    }

    suspend fun setContext(
        systemPrompt: String,
        examples: List<Pair<String, String>>,
    ) = lock.withLock {
        if (handle == 0L) return@withLock
        LlamaBridge.nativeSetContext(
            handle,
            systemPrompt,
            examples.map { it.first }.toTypedArray(),
            examples.map { it.second }.toTypedArray(),
        )
    }

    suspend fun reset() = lock.withLock { if (handle != 0L) LlamaBridge.nativeReset(handle) }

    suspend fun unload() = lock.withLock {
        if (handle != 0L) {
            LlamaBridge.nativeUnload(handle)
            handle = 0L
        }
    }

    /** Blocking generation; [onPiece] receives streamed text. */
    suspend fun send(text: String, onPiece: ((String) -> Boolean)? = null): String = lock.withLock {
        withContext(Dispatchers.Default) {
            if (handle == 0L) throw IllegalStateException("모델이 로드되지 않았습니다")
            val callback = onPiece?.let { TokenCallback { piece -> onPiece(piece) } }
            val reply = LlamaBridge.nativeSend(handle, text, callback)
            if (reply.startsWith("ERR:")) throw IllegalStateException(reply.removePrefix("ERR: "))
            reply
        }
    }

    override fun finalize() {
        if (handle != 0L) {
            LlamaBridge.nativeDestroy(handle)
            handle = 0L
        }
    }
}
