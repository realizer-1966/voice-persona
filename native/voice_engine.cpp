// voice_engine.cpp - llama.cpp chat engine implementation.
#include "voice_engine.h"

#include "llama.h"

#include <cstdio>
#include <cstring>
#include <utility>

namespace voicepersona {

namespace {

// Adjacent literals keep the tags readable and avoid any chance of a
// half-written angle bracket in the source.
const std::string THINK_OPEN  = "<" "think" ">";
const std::string THINK_CLOSE = "<" "/" "think" ">";

// True while `s` could still turn out to start with THINK_OPEN.
bool could_open_tag(const std::string & s) {
    if (s.size() > THINK_OPEN.size()) {
        return false;
    }
    return THINK_OPEN.compare(0, s.size(), s) == 0;
}

bool starts_with(const std::string & s, const std::string & prefix) {
    return s.size() >= prefix.size() && std::memcmp(s.data(), prefix.data(), prefix.size()) == 0;
}

// Keeps only letters and digits, so "안녕! 오늘 뭐 했어?" matches "안녕 오늘 뭐 했어".
std::string normalize(const std::string & s) {
    std::string out;
    for (unsigned char c : s) {
        if (c >= 0x80 || (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
            out += (char) c;
        }
    }
    return out;
}

std::string trim_ws(const std::string & s) {
    size_t b = 0;
    size_t e = s.size();
    while (b < e && (s[b] == ' ' || s[b] == '\n' || s[b] == '\r' || s[b] == '\t')) {
        ++b;
    }
    while (e > b && (s[e - 1] == ' ' || s[e - 1] == '\n' || s[e - 1] == '\r' || s[e - 1] == '\t')) {
        --e;
    }
    return s.substr(b, e - b);
}

}  // namespace

// Strips a leading reasoning block if the model produced one.
std::string ChatEngine::strip_thinking(const std::string & raw) {
    std::string s = raw;
    const size_t close = s.find(THINK_CLOSE);
    if (close != std::string::npos) {
        // Reasoning finished: keep only what follows the closing tag.
        s = s.substr(close + THINK_CLOSE.size());
    } else {
        // No closing tag. Some templates open the block in the assistant
        // prefix, so also drop a leading opening tag when one is present.
        const size_t open = s.find(THINK_OPEN);
        if (open != std::string::npos && open <= 1) {
            s = s.substr(open + THINK_OPEN.size());
        }
    }
    return trim_ws(s);
}

// The model often repeats the question back. Find where that echo ends in the
// original (unstripped) reply and cut it off.
std::string ChatEngine::strip_echo(const std::string & reply, const std::string & user_text) {
    const std::string want = normalize(user_text);
    if (want.size() < 2) {
        return reply;
    }
    const std::string have = normalize(reply);
    if (have.size() < want.size()) {
        return reply;
    }
    if (have.compare(0, want.size(), want) != 0) {
        return reply;
    }
    // Walk the original reply until it has accounted for the echoed characters.
    size_t seen = 0;
    size_t at   = 0;
    for (; at < reply.size(); ++at) {
        const unsigned char c = (unsigned char) reply[at];
        if (c >= 0x80 || (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
            ++seen;
        }
        if (seen >= want.size()) {
            ++at;
            break;
        }
    }
    std::string rest = reply.substr(at);
    if (rest.size() < 2) {
        return reply;   // the whole reply was the echo; keep it rather than answer nothing
    }
    return trim_ws(rest);
}

// Holds the pending output until we know whether it opens a reasoning block,
// so reasoning never reaches the UI.
class ThinkGate {
public:
    explicit ThinkGate(const TokenSink & sink) : sink_(sink) {}

    void feed(const std::string & piece) {
        raw_ += piece;
        if (done_) {
            emit(piece);
            return;
        }
        buf_ += piece;

        const size_t close = buf_.find(THINK_CLOSE);
        if (close != std::string::npos) {
            const std::string rest = buf_.substr(close + THINK_CLOSE.size());
            done_ = true;
            buf_.clear();
            emit(rest);
            return;
        }
        if (starts_with(buf_, THINK_OPEN)) {
            // Explicitly opened a reasoning block; hold output until it closes.
            return;
        }
        if (could_open_tag(buf_)) {
            return;  // still undecided, keep buffering
        }
        done_ = true;
        const std::string flush = buf_;
        buf_.clear();
        emit(flush);
    }

    bool stop() const { return stop_; }

private:
    void emit(const std::string & text) {
        if (!sink_ || text.empty()) {
            return;
        }
        if (!sink_(text)) {
            stop_ = true;
        }
    }

    const TokenSink & sink_;
    std::string raw_;
    std::string buf_;
    bool done_  = false;
    bool stop_  = false;
};

struct ChatEngine::Impl {
    llama_model *   model = nullptr;
    llama_context * ctx   = nullptr;
    const llama_vocab * vocab = nullptr;

    GenParams params;

    std::vector<Turn>  history;
    std::vector<int32_t> kv_tokens;   // token sequence currently in the KV cache
    int  n_past    = 0;
    bool has_prev  = false;

    std::string last_raw;
    int         last_prompt_tokens = 0;

    void free_ctx() {
        if (ctx) {
            llama_free(ctx);
            ctx = nullptr;
        }
        kv_tokens.clear();
        n_past   = 0;
        has_prev = false;
    }
};

ChatEngine::ChatEngine() : d(new Impl()) {}

ChatEngine::~ChatEngine() {
    unload();
    delete d;
}

void ChatEngine::unload() {
    d->free_ctx();
    if (d->model) {
        llama_model_free(d->model);
        d->model = nullptr;
    }
    d->vocab = nullptr;
}

bool ChatEngine::loaded() const {
    return d->model != nullptr && d->ctx != nullptr;
}

bool ChatEngine::load(const std::string & model_path, const GenParams & params, std::string & err) {
    unload();
    d->params = params;

    llama_backend_init();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;   // CPU only: the app ships no GPU backend

    d->model = llama_model_load_from_file(model_path.c_str(), mp);
    if (!d->model) {
        err = "failed to load model: " + model_path;
        return false;
    }
    d->vocab = llama_model_get_vocab(d->model);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = (uint32_t) params.n_ctx;
    cp.n_batch         = (uint32_t) params.n_batch;
    cp.n_ubatch        = (uint32_t) params.n_batch;
    cp.n_threads       = params.n_threads;
    cp.n_threads_batch = params.n_threads;

    d->ctx = llama_init_from_model(d->model, cp);
    if (!d->ctx) {
        err = "failed to create context";
        llama_model_free(d->model);
        d->model = nullptr;
        return false;
    }
    return true;
}

void ChatEngine::reset() {
    if (d->ctx) {
        llama_memory_clear(llama_get_memory(d->ctx), true);
    }
    d->kv_tokens.clear();
    d->n_past   = 0;
    d->has_prev = false;
}

void ChatEngine::set_history(const std::vector<Turn> & history) {
    d->history = history;
    reset();
}

void ChatEngine::set_system(const std::string & system_prompt) {
    std::vector<Turn> kept;
    kept.push_back({"system", system_prompt});
    for (const Turn & t : d->history) {
        if (t.role != "system") {
            kept.push_back(t);
        }
    }
    d->history = kept;
}

int ChatEngine::history_size() const {
    return (int) d->history.size();
}

int ChatEngine::last_prompt_tokens() const {
    return d->last_prompt_tokens;
}

std::string ChatEngine::last_raw() const {
    return d->last_raw;
}

std::string ChatEngine::info() const {
    if (!d->model) {
        return "not loaded";
    }
    char desc[256] = {0};
    llama_model_desc(d->model, desc, sizeof(desc));
    char buf[512];
    std::snprintf(buf, sizeof(buf), "%s | n_ctx=%u train_ctx=%d vocab=%d",
                  desc, llama_n_ctx(d->ctx), llama_model_n_ctx_train(d->model),
                  llama_vocab_n_tokens(d->vocab));
    return std::string(buf);
}

std::string ChatEngine::apply_template(bool add_assistant) const {
    std::vector<llama_chat_message> msgs;
    msgs.reserve(d->history.size());
    for (const Turn & t : d->history) {
        msgs.push_back({t.role.c_str(), t.content.c_str()});
    }
    const char * tmpl = llama_model_chat_template(d->model, nullptr);

    std::string out;
    int32_t need = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), add_assistant,
                                             nullptr, 0);
    if (need < 0) {
        // No usable template: fall back to a plain ChatML scaffold.
        out.clear();
        for (const Turn & t : d->history) {
            out += "<|im_start|>" + t.role + "\n" + t.content + "<|im_end|>\n";
        }
        if (add_assistant) {
            out += "<|im_start|>assistant\n";
            if (d->params.disable_thinking) {
                out += THINK_OPEN + "\n\n" + THINK_CLOSE + "\n\n";
            }
        }
        return out;
    }
    out.resize((size_t) need + 1);
    llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), add_assistant, out.data(),
                              (int32_t) out.size());
    out.resize((size_t) need);
    if (add_assistant && d->params.disable_thinking) {
        // Same rendering Qwen3 uses for enable_thinking=false.
        out += THINK_OPEN + "\n\n" + THINK_CLOSE + "\n\n";
    }
    return out;
}

std::vector<int32_t> ChatEngine::tokenize(const std::string & text) const {
    std::vector<int32_t> tokens;
    if (!d->vocab) {
        return tokens;
    }
    int32_t n = llama_tokenize(d->vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, true, true);
    if (n < 0) {
        n = -n;
    }
    if (n == 0) {
        return tokens;
    }
    tokens.resize((size_t) n);
    const int32_t got = llama_tokenize(d->vocab, text.c_str(), (int32_t) text.size(),
                                       tokens.data(), n, true, true);
    if (got < 0) {
        tokens.clear();
        return tokens;
    }
    tokens.resize((size_t) got);
    return tokens;
}

std::string ChatEngine::send(const std::string & user_text, const TokenSink & sink,
                             std::string & err) {
    err.clear();
    if (!loaded()) {
        err = "model not loaded";
        return "";
    }
    d->history.push_back({"user", user_text});

    std::string prompt = apply_template(true);
    std::vector<int32_t> toks = tokenize(prompt);
    if (toks.empty()) {
        err = "tokenize failed";
        return "";
    }

    // Keep room for the reply. Drop the oldest non-system turns until it fits.
    const int budget = d->params.n_ctx - d->params.n_predict - 8;
    while ((int) toks.size() > budget && d->history.size() > 2) {
        size_t victim = 0;
        for (size_t i = 0; i < d->history.size(); ++i) {
            if (d->history[i].role != "system") {
                victim = i;
                break;
            }
        }
        d->history.erase(d->history.begin() + (long) victim);
        // The dropped turn may be the user turn we just added.
        if (d->history.empty() || d->history.back().role != "user") {
            d->history.push_back({"user", user_text});
        }
        prompt = apply_template(true);
        toks   = tokenize(prompt);
    }
    if ((int) toks.size() > budget) {
        err = "prompt too long even after trimming";
        return "";
    }
    d->last_prompt_tokens = (int) toks.size();

    // Reuse the KV prefix shared with the previous run.
    const int kv_len = (int) d->kv_tokens.size();
    size_t common = 0;
    if (d->has_prev) {
        while (common < d->kv_tokens.size() && common < toks.size() &&
               d->kv_tokens[common] == toks[common]) {
            ++common;
        }
        if ((int) common > kv_len) {
            common = (size_t) kv_len;
        }
    }
    if (common == 0) {
        llama_memory_clear(llama_get_memory(d->ctx), true);
    } else {
        llama_memory_seq_rm(llama_get_memory(d->ctx), 0, (llama_pos) common, -1);
    }
    d->n_past   = (int) common;
    d->has_prev = true;

    // Prefill the new part of the prompt.
    for (size_t i = common; i < toks.size(); ) {
        const int chunk = (int) std::min((size_t) d->params.n_batch, toks.size() - i);
        llama_batch batch = llama_batch_get_one(toks.data() + i, chunk);
        const int32_t rc  = llama_decode(d->ctx, batch);
        if (rc != 0) {
            err = "decode failed during prefill (rc=" + std::to_string(rc) + ")";
            d->has_prev = false;
            return "";
        }
        i      += (size_t) chunk;
        d->n_past += chunk;
    }
    d->kv_tokens = toks;
    d->kv_tokens.reserve(toks.size() + (size_t) d->params.n_predict);

    // Fresh sampler chain per turn: no state leaks between turns.
    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    llama_sampler * chain = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(chain, llama_sampler_init_penalties(
                                       llama_vocab_n_tokens(d->vocab),
                                       d->params.repeat_last_n,
                                       d->params.repeat_penalty, 0.0f, 0.3f));
    llama_sampler_chain_add(chain, llama_sampler_init_top_k(d->params.top_k));
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(d->params.top_p, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(d->params.temp));
    llama_sampler_chain_add(chain, llama_sampler_init_dist((uint32_t) d->params.seed));

    ThinkGate gate(sink);
    std::string piece;
    std::string raw;
    int generated = 0;
    bool early_stop = false;

    while (generated < d->params.n_predict) {
        const llama_token tok = llama_sampler_sample(chain, d->ctx, -1);
        if (llama_vocab_is_eog(d->vocab, tok)) {
            break;
        }
        char buf[256];
        const int32_t nb = llama_token_to_piece(d->vocab, tok, buf, sizeof(buf), 0, false);
        if (nb > 0) {
            piece.assign(buf, (size_t) nb);
            raw += piece;
            gate.feed(piece);
            if (gate.stop()) {
                early_stop = true;
                break;
            }
            if (d->params.stop_at_paragraph && generated > 8) {
                const std::string visible = strip_thinking(raw);
                const size_t para = visible.find("\n\n");
                if (para != std::string::npos && para + 2 < visible.size()) {
                    early_stop = true;
                    break;
                }
            }
        }
        llama_sampler_accept(chain, tok);
        d->kv_tokens.push_back(tok);

        llama_batch batch = llama_batch_get_one((llama_token *) &tok, 1);
        const int32_t rc  = llama_decode(d->ctx, batch);
        if (rc != 0) {
            err = "decode failed during generation (rc=" + std::to_string(rc) + ")";
            break;
        }
        ++d->n_past;
        ++generated;
    }

    llama_sampler_free(chain);
    (void) early_stop;

    d->last_raw = raw;
    const std::string reply = strip_echo(strip_thinking(raw), user_text);
    d->history.push_back({"assistant", reply});
    return reply;
}

}  // namespace voicepersona
