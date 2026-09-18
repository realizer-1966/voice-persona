// asr_engine.cpp - transcribe.cpp wrapper.
#include "asr_engine.h"

#include "transcribe.h"

#include <cstdio>
#include <cstring>

namespace voicepersona {

struct AsrEngine::Impl {
    transcribe_session * session = nullptr;
    transcribe_model *   model   = nullptr;
    int                  n_threads = 4;
};

AsrEngine::AsrEngine() : d(new Impl()) {}

AsrEngine::~AsrEngine() {
    unload();
    delete d;
}

void AsrEngine::unload() {
    // Sessions borrow the model, so free the session before the model.
    if (d->session) {
        transcribe_session_free(d->session);
        d->session = nullptr;
    }
    if (d->model) {
        transcribe_model_free(d->model);
        d->model = nullptr;
    }
}

bool AsrEngine::loaded() const {
    return d->session != nullptr;
}

bool AsrEngine::load(const std::string & model_path, int n_threads, std::string & err) {
    unload();
    d->n_threads = n_threads > 0 ? n_threads : 4;

    const transcribe_status rc_init = transcribe_init_backends_default();
    if (rc_init != TRANSCRIBE_OK) {
        err = "transcribe_init_backends_default failed: " + std::to_string((int) rc_init);
        return false;
    }

    transcribe_model_load_params lp;
    transcribe_model_load_params_init(&lp);

    const transcribe_status rc_model =
        transcribe_model_load_file(model_path.c_str(), &lp, &d->model);
    if (rc_model != TRANSCRIBE_OK || d->model == nullptr) {
        d->model = nullptr;
        err = "transcribe_model_load_file failed: " + std::to_string((int) rc_model);
        return false;
    }

    transcribe_session_params sp;
    transcribe_session_params_init(&sp);
    sp.n_threads = d->n_threads;

    const transcribe_status rc_sess = transcribe_session_init(d->model, &sp, &d->session);
    if (rc_sess != TRANSCRIBE_OK || d->session == nullptr) {
        d->session = nullptr;
        transcribe_model_free(d->model);
        d->model = nullptr;
        err = "transcribe_session_init failed: " + std::to_string((int) rc_sess);
        return false;
    }
    return true;
}

std::string AsrEngine::transcribe(const float * pcm, int n_samples, std::string & err) {
    err.clear();
    if (!d->session) {
        err = "asr model not loaded";
        return "";
    }
    const transcribe_status rc = transcribe_run(d->session, pcm, n_samples, nullptr);
    if (rc != TRANSCRIBE_OK) {
        err = "transcribe_run failed: " + std::to_string((int) rc);
        return "";
    }
    const char * text = transcribe_full_text(d->session);
    return text ? std::string(text) : std::string();
}

std::string AsrEngine::info() const {
    if (!d->model) {
        return "not loaded";
    }
    char buf[256];
    const char * arch    = transcribe_model_arch_string(d->model);
    const char * variant = transcribe_model_variant_string(d->model);
    const char * backend = transcribe_model_backend(d->model);
    std::snprintf(buf, sizeof(buf), "arch=%s variant=%s backend=%s",
                  arch ? arch : "?", variant ? variant : "?", backend ? backend : "?");
    return std::string(buf);
}

}  // namespace voicepersona
