// asr_engine.h - speech-to-text over transcribe.cpp. No JNI here.
#pragma once

#include <string>

namespace voicepersona {

class AsrEngine {
public:
    AsrEngine();
    ~AsrEngine();
    AsrEngine(const AsrEngine &) = delete;
    AsrEngine & operator=(const AsrEngine &) = delete;

    bool load(const std::string & model_path, int n_threads, std::string & err);
    bool loaded() const;
    void unload();

    // 16 kHz mono float32 in [-1, 1]. Blocking.
    std::string transcribe(const float * pcm, int n_samples, std::string & err);

    std::string info() const;

private:
    struct Impl;
    Impl * d;
};

}  // namespace voicepersona
