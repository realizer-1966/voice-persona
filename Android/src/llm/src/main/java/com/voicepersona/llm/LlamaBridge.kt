package com.voicepersona.llm

/**
 * JNI surface for the llama.cpp chat engine. All calls are blocking; run them on a
 * worker thread and keep them serialized (the native engine is not reentrant).
 */
object LlamaBridge {
    init {
        System.loadLibrary("voicepersona_llm")
    }

    external fun nativeCreate(): Long
    external fun nativeDestroy(handle: Long)
    external fun nativeLoad(handle: Long, modelPath: String, nThreads: Int, nCtx: Int, nPredict: Int): String
    external fun nativeIsLoaded(handle: Long): Boolean
    external fun nativeUnload(handle: Long)
    external fun nativeSetSystem(handle: Long, systemPrompt: String)

    /** System prompt plus few-shot turns recovered from the user's recordings. */
    external fun nativeSetContext(
        handle: Long,
        systemPrompt: String,
        userTurns: Array<String>,
        assistantTurns: Array<String>,
    )
    external fun nativeReset(handle: Long)
    external fun nativeSend(handle: Long, text: String, callback: TokenCallback?): String
    external fun nativeInfo(handle: Long): String
}

/** Receives streamed reply pieces. Return false to stop generation early. */
fun interface TokenCallback {
    fun onToken(piece: String): Boolean
}
