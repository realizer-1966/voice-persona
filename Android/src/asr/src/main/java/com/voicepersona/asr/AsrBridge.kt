package com.voicepersona.asr

/**
 * JNI surface for the transcribe.cpp speech engine. Blocking; serialize calls.
 */
object AsrBridge {
    init {
        System.loadLibrary("voicepersona_asr")
    }

    external fun nativeCreate(): Long
    external fun nativeDestroy(handle: Long)
    external fun nativeLoad(handle: Long, modelPath: String, nThreads: Int): String

    /** True when the last run ran out of generation budget (partial transcript). */
    external fun nativeWasTruncated(handle: Long): Boolean

    /** Longest audio this session accepts in one call, in milliseconds. */
    external fun nativeMaxAudioMs(handle: Long): Long
    external fun nativeIsLoaded(handle: Long): Boolean
    external fun nativeUnload(handle: Long)

    /** [pcm] must be 16 kHz mono float32 in [-1, 1]. */
    external fun nativeTranscribe(handle: Long, pcm: FloatArray, nSamples: Int): String

    external fun nativeLoadDiarizer(handle: Long, modelPath: String, nThreads: Int): String

    /** Flat triples: t0_ms, t1_ms, speaker_id. Null when nothing was produced. */
    external fun nativeDiarize(handle: Long, pcm: FloatArray, nSamples: Int): IntArray?
    external fun nativeInfo(handle: Long): String
}
