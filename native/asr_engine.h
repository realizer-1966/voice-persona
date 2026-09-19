// asr_engine.h - speech-to-text over transcribe.cpp. No JNI here.
#pragma once

#include <string>
#include <vector>

namespace voicepersona {

/** One diarized span: speaker_id is 1-based in order of first appearance. */
struct SpeakerSpan {
    int t0_ms      = 0;
    int t1_ms      = 0;
    int speaker_id = 0;
};

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

    // ---- speaker diarization (separate model, no transcript) ----
    // Loads a diarizer alongside the ASR model. Optional: transcription works
    // without it.
    bool load_diarizer(const std::string & model_path, int n_threads, std::string & err);
    bool diarizer_loaded() const;

    // Speaker spans for the same 16 kHz mono input. Empty when no diarizer is
    // loaded or the model produced nothing.
    std::vector<SpeakerSpan> diarize(const float * pcm, int n_samples, std::string & err);

private:
    struct Impl;
    Impl * d;
};

}  // namespace voicepersona
