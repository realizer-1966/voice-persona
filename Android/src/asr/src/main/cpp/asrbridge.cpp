// asrbridge.cpp - JNI surface for the transcribe.cpp speech engine.
#include <jni.h>

#include <string>
#include <vector>

#include "asr_engine.h"
#include "jni_common.h"

using voicepersona::AsrEngine;

namespace {

AsrEngine * engine_from(jlong handle) {
    return reinterpret_cast<AsrEngine *>(handle);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_voicepersona_asr_AsrBridge_nativeCreate(JNIEnv *, jclass) {
    return reinterpret_cast<jlong>(new AsrEngine());
}

JNIEXPORT void JNICALL
Java_com_voicepersona_asr_AsrBridge_nativeDestroy(JNIEnv *, jclass, jlong h) {
    delete engine_from(h);
}

// Returns model info on success, or "ERR: ..." on failure.
JNIEXPORT jstring JNICALL
Java_com_voicepersona_asr_AsrBridge_nativeLoad(JNIEnv * env, jclass, jlong h, jstring path,
                                              jint n_threads) {
    AsrEngine * engine = engine_from(h);
    if (!engine) {
        return jniutil::to_java(env, "ERR: null handle");
    }
    std::string err;
    if (!engine->load(jniutil::to_std(env, path), n_threads, err)) {
        return jniutil::to_java(env, "ERR: " + err);
    }
    return jniutil::to_java(env, engine->info());
}

JNIEXPORT jboolean JNICALL
Java_com_voicepersona_asr_AsrBridge_nativeIsLoaded(JNIEnv *, jclass, jlong h) {
    AsrEngine * engine = engine_from(h);
    return (engine && engine->loaded()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_voicepersona_asr_AsrBridge_nativeUnload(JNIEnv *, jclass, jlong h) {
    AsrEngine * engine = engine_from(h);
    if (engine) {
        engine->unload();
    }
}

// pcm must be 16 kHz mono float32 in [-1, 1].
JNIEXPORT jstring JNICALL
Java_com_voicepersona_asr_AsrBridge_nativeTranscribe(JNIEnv * env, jclass, jlong h,
                                                    jfloatArray pcm, jint n_samples) {
    AsrEngine * engine = engine_from(h);
    if (!engine) {
        return jniutil::to_java(env, "ERR: null handle");
    }
    std::string err;
    if (pcm == nullptr || n_samples <= 0) {
        return jniutil::to_java(env, "ERR: empty audio");
    }
    jfloat * data = env->GetFloatArrayElements(pcm, nullptr);
    if (!data) {
        return jniutil::to_java(env, "ERR: cannot read audio buffer");
    }
    const std::string text = engine->transcribe(data, (int) n_samples, err);
    env->ReleaseFloatArrayElements(pcm, data, JNI_ABORT);

    if (!err.empty()) {
        return jniutil::to_java(env, "ERR: " + err);
    }
    return jniutil::to_java(env, text);
}

// Loads a speaker diarizer beside the ASR model. Returns info or "ERR: ...".
JNIEXPORT jstring JNICALL
Java_com_voicepersona_asr_AsrBridge_nativeLoadDiarizer(JNIEnv * env, jclass, jlong h,
                                                      jstring path, jint n_threads) {
    AsrEngine * engine = engine_from(h);
    if (!engine) {
        return jniutil::to_java(env, "ERR: null handle");
    }
    std::string err;
    if (!engine->load_diarizer(jniutil::to_std(env, path), n_threads, err)) {
        return jniutil::to_java(env, "ERR: " + err);
    }
    return jniutil::to_java(env, "diarizer ready");
}

// Returns int[3 * n]: t0_ms, t1_ms, speaker_id per speaker segment.
JNIEXPORT jintArray JNICALL
Java_com_voicepersona_asr_AsrBridge_nativeDiarize(JNIEnv * env, jclass, jlong h,
                                                 jfloatArray pcm, jint n_samples) {
    AsrEngine * engine = engine_from(h);
    if (!engine || pcm == nullptr || n_samples <= 0) {
        return nullptr;
    }
    jfloat * data = env->GetFloatArrayElements(pcm, nullptr);
    if (!data) {
        return nullptr;
    }
    std::string err;
    const std::vector<voicepersona::SpeakerSpan> spans =
        engine->diarize(data, (int) n_samples, err);
    env->ReleaseFloatArrayElements(pcm, data, JNI_ABORT);

    const jsize out_size = (jsize) (spans.size() * 3);
    jintArray out = env->NewIntArray(out_size);
    if (out == nullptr || spans.empty()) {
        return out;
    }
    std::vector<jint> flat;
    flat.reserve((size_t) out_size);
    for (const auto & span : spans) {
        flat.push_back((jint) span.t0_ms);
        flat.push_back((jint) span.t1_ms);
        flat.push_back((jint) span.speaker_id);
    }
    env->SetIntArrayRegion(out, 0, out_size, flat.data());
    return out;
}

// True when the last run hit the family generation budget (transcript partial).
JNIEXPORT jboolean JNICALL
Java_com_voicepersona_asr_AsrBridge_nativeWasTruncated(JNIEnv *, jclass, jlong h) {
    AsrEngine * engine = engine_from(h);
    return (engine && engine->last_run_truncated()) ? JNI_TRUE : JNI_FALSE;
}

// Longest audio this session accepts in one call, in milliseconds.
JNIEXPORT jlong JNICALL
Java_com_voicepersona_asr_AsrBridge_nativeMaxAudioMs(JNIEnv *, jclass, jlong h) {
    AsrEngine * engine = engine_from(h);
    return engine ? (jlong) engine->max_audio_ms() : 0;
}

JNIEXPORT jstring JNICALL
Java_com_voicepersona_asr_AsrBridge_nativeInfo(JNIEnv * env, jclass, jlong h) {
    AsrEngine * engine = engine_from(h);
    return jniutil::to_java(env, engine ? engine->info() : "not loaded");
}

}  // extern "C"
