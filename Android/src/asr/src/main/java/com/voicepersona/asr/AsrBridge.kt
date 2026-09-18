package com.voicepersona.asr

/**
 * JNI surface for the transcribe.cpp speech engine. Blocking; serialize calls.
 */
internal object AsrBridge {
    init {
        System.loadLibrary("voicepersona_asr")
    }

    external fun nativeCreate(): Long
    external fun nativeDestroy(handle: Long)
    external fun nativeLoad(handle: Long, modelPath: String, nThreads: Int): String
    external fun nativeIsLoaded(handle: Long): Boolean
    external fun nativeUnload(handle: Long)

    /** [pcm] must be 16 kHz mono float32 in [-1, 1]. */
    external fun nativeTranscribe(handle: Long, pcm: FloatArray, nSamples: Int): String
    external fun nativeInfo(handle: Long): String
}
