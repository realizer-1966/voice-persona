// jni_common.h - small helpers shared by the JNI bridges.
#pragma once

#include <jni.h>

#include <string>

namespace jniutil {

inline std::string to_std(JNIEnv * env, jstring s) {
    if (s == nullptr) {
        return std::string();
    }
    const char * chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars ? chars : "";
    if (chars) {
        env->ReleaseStringUTFChars(s, chars);
    }
    return out;
}

inline jstring to_java(JNIEnv * env, const std::string & s) {
    return env->NewStringUTF(s.c_str());
}

}  // namespace jniutil
