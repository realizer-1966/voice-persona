// voice_engine.h - chat engine over llama.cpp. No JNI here so the core can be
// compiled and tested on a desktop host.
#pragma once

#include <cstdint>
#include <functional>
#include <string>
#include <vector>

namespace voicepersona {

struct GenParams {
    int   n_predict      = 256;
    int   n_ctx          = 2048;
    int   n_threads      = 4;
    int   n_batch        = 512;
    float temp           = 0.8f;
    float top_p          = 0.95f;
    int   top_k          = 40;
    float repeat_penalty = 1.10f;
    int   repeat_last_n  = 64;
    int   seed           = 1234;
    // Qwen3-family models open a reasoning block by default. Pre-closing it in
    // the prompt (the enable_thinking=false rendering) stops the model from
    // burning its budget on an empty reasoning pass.
    bool  disable_thinking = true;
    // Small models loop after their first thought. Stop at the first blank line.
    bool  stop_at_paragraph = true;
};

struct Turn {
    std::string role;     // "system" | "user" | "assistant"
    std::string content;
};

// Streaming sink. Return false to request an early stop.
using TokenSink = std::function<bool(const std::string & piece)>;

class ChatEngine {
public:
    ChatEngine();
    ~ChatEngine();
    ChatEngine(const ChatEngine &) = delete;
    ChatEngine & operator=(const ChatEngine &) = delete;

    bool load(const std::string & model_path, const GenParams & params, std::string & err);
    bool loaded() const;
    void reset();
    void unload();

    // Replaces the whole conversation. Clears the KV cache.
    void set_history(const std::vector<Turn> & history);
    void set_system(const std::string & system_prompt);

    // Appends a user turn, generates, appends the assistant turn. Blocking.
    // Returns the assistant reply with any reasoning block stripped.
    std::string send(const std::string & user_text, const TokenSink & sink, std::string & err);

    // Raw model text for the last run (before thinking-strip).
    std::string last_raw() const;

    int  history_size() const;
    int  last_prompt_tokens() const;
    std::string info() const;

    static std::string strip_thinking(const std::string & raw);
    // Drops a leading echo of the user's own sentence.
    static std::string strip_echo(const std::string & reply, const std::string & user_text);

private:
    std::string apply_template(bool add_assistant) const;
    std::vector<int32_t> tokenize(const std::string & text) const;

    struct Impl;
    Impl * d;
};

}  // namespace voicepersona
