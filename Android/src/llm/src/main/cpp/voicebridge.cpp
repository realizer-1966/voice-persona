// voicebridge.cpp - JNI surface for the llama.cpp chat engine.
#include <jni.h>

#include <string>
#include <vector>

#include "jni_common.h"
#include "voice_engine.h"

using voicepersona::ChatEngine;
using voicepersona::GenParams;

namespace {

ChatEngine * engine_from(jlong handle) {
    return reinterpret_cast<ChatEngine *>(handle);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_voicepersona_llm_LlamaBridge_nativeCreate(JNIEnv *, jclass) {
    return reinterpret_cast<jlong>(new ChatEngine());
}

JNIEXPORT void JNICALL
Java_com_voicepersona_llm_LlamaBridge_nativeDestroy(JNIEnv *, jclass, jlong h) {
    delete engine_from(h);
}

// Returns the model description on success, or "ERR: ..." on failure.
JNIEXPORT jstring JNICALL
Java_com_voicepersona_llm_LlamaBridge_nativeLoad(JNIEnv * env, jclass, jlong h, jstring path,
                                                jint n_threads, jint n_ctx, jint n_predict) {
    ChatEngine * engine = engine_from(h);
    if (!engine) {
        return jniutil::to_java(env, "ERR: null handle");
    }
    GenParams params;
    params.n_threads = n_threads;
    params.n_ctx     = n_ctx;
    params.n_predict = n_predict;

    std::string err;
    if (!engine->load(jniutil::to_std(env, path), params, err)) {
        return jniutil::to_java(env, "ERR: " + err);
    }
    return jniutil::to_java(env, engine->info());
}

JNIEXPORT jboolean JNICALL
Java_com_voicepersona_llm_LlamaBridge_nativeIsLoaded(JNIEnv *, jclass, jlong h) {
    ChatEngine * engine = engine_from(h);
    return (engine && engine->loaded()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_voicepersona_llm_LlamaBridge_nativeUnload(JNIEnv *, jclass, jlong h) {
    ChatEngine * engine = engine_from(h);
    if (engine) {
        engine->unload();
    }
}

JNIEXPORT void JNICALL
Java_com_voicepersona_llm_LlamaBridge_nativeSetSystem(JNIEnv * env, jclass, jlong h,
                                                     jstring system_prompt) {
    ChatEngine * engine = engine_from(h);
    if (engine) {
        engine->set_system(jniutil::to_std(env, system_prompt));
    }
}

// Installs the system prompt plus optional few-shot turns taken from the
// user's own recordings. Two parallel arrays, matched by index.
JNIEXPORT void JNICALL
Java_com_voicepersona_llm_LlamaBridge_nativeSetContext(JNIEnv * env, jclass, jlong h,
                                                      jstring system_prompt,
                                                      jobjectArray user_turns,
                                                      jobjectArray assistant_turns) {
    ChatEngine * engine = engine_from(h);
    if (!engine) {
        return;
    }
    std::vector<voicepersona::Turn> history;
    history.push_back({"system", jniutil::to_std(env, system_prompt)});

    const jsize n_users = user_turns ? env->GetArrayLength(user_turns) : 0;
    const jsize n_asst  = assistant_turns ? env->GetArrayLength(assistant_turns) : 0;
    const jsize pairs   = n_users < n_asst ? n_users : n_asst;
    for (jsize i = 0; i < pairs; ++i) {
        auto user = (jstring) env->GetObjectArrayElement(user_turns, i);
        auto asst = (jstring) env->GetObjectArrayElement(assistant_turns, i);
        history.push_back({"user", jniutil::to_std(env, user)});
        history.push_back({"assistant", jniutil::to_std(env, asst)});
        env->DeleteLocalRef(user);
        env->DeleteLocalRef(asst);
    }
    engine->set_history(history);
}

JNIEXPORT void JNICALL
Java_com_voicepersona_llm_LlamaBridge_nativeReset(JNIEnv *, jclass, jlong h) {
    ChatEngine * engine = engine_from(h);
    if (engine) {
        engine->reset();
    }
}

// Streams pieces into callback.onToken(String) and returns the final reply.
JNIEXPORT jstring JNICALL
Java_com_voicepersona_llm_LlamaBridge_nativeSend(JNIEnv * env, jclass, jlong h, jstring text,
                                                jobject callback) {
    ChatEngine * engine = engine_from(h);
    if (!engine) {
        return jniutil::to_java(env, "ERR: null handle");
    }

    jmethodID on_token = nullptr;
    if (callback) {
        jclass cls = env->GetObjectClass(callback);
        on_token = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)Z");
    }

    std::string err;
    const std::string reply = engine->send(
        jniutil::to_std(env, text),
        [&](const std::string & piece) -> bool {
            if (!on_token || !callback) {
                return true;
            }
            jstring arg = jniutil::to_java(env, piece);
            const jboolean keep_going = env->CallBooleanMethod(callback, on_token, arg);
            env->DeleteLocalRef(arg);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                return false;
            }
            return keep_going == JNI_TRUE;
        },
        err);

    if (!err.empty()) {
        return jniutil::to_java(env, "ERR: " + err);
    }
    return jniutil::to_java(env, reply);
}

JNIEXPORT jstring JNICALL
Java_com_voicepersona_llm_LlamaBridge_nativeInfo(JNIEnv * env, jclass, jlong h) {
    ChatEngine * engine = engine_from(h);
    return jniutil::to_java(env, engine ? engine->info() : "not loaded");
}

}  // extern "C"
