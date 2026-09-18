// host_test/main.cpp - desktop harness: proves the engines work before the APK.
#include "../asr_engine.h"
#include "../voice_engine.h"

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace {

struct Wav {
    std::vector<float> pcm;   // mono float32
    int sample_rate = 0;
};

bool read_wav(const char * path, Wav & out) {
    FILE * f = std::fopen(path, "rb");
    if (!f) {
        std::printf("  cannot open %s\n", path);
        return false;
    }
    char riff[4], wave[4];
    uint32_t chunk = 0;
    if (std::fread(riff, 1, 4, f) != 4 || std::memcmp(riff, "RIFF", 4) != 0) { std::fclose(f); return false; }
    std::fread(&chunk, 4, 1, f);
    if (std::fread(wave, 1, 4, f) != 4 || std::memcmp(wave, "WAVE", 4) != 0) { std::fclose(f); return false; }

    int channels = 1, bits = 16;
    uint16_t fmt = 0;
    while (std::fread(riff, 1, 4, f) == 4) {
        uint32_t size = 0;
        if (std::fread(&size, 4, 1, f) != 1) break;
        if (std::memcmp(riff, "fmt ", 4) == 0) {
            uint16_t ch = 1, b = 16;
            uint32_t rate = 16000;
            std::fread(&fmt, 2, 1, f);
            std::fread(&ch, 2, 1, f);
            std::fread(&rate, 4, 1, f);
            std::fread(&chunk, 4, 1, f);
            std::fread(&chunk, 2, 1, f);
            std::fread(&b, 2, 1, f);
            channels = ch; bits = b; out.sample_rate = (int) rate;
            if (size > 16) std::fseek(f, (long) (size - 16), SEEK_CUR);
        } else if (std::memcmp(riff, "data", 4) == 0) {
            const size_t n = size / (size_t) (bits / 8);
            std::vector<char> raw(size);
            std::fread(raw.data(), 1, size, f);
            out.pcm.resize(n / (size_t) channels);
            for (size_t i = 0; i < out.pcm.size(); ++i) {
                double acc = 0.0;
                for (int c = 0; c < channels; ++c) {
                    const size_t idx = i * (size_t) channels + (size_t) c;
                    if (bits == 16) {
                        int16_t v = 0;
                        std::memcpy(&v, raw.data() + idx * 2, 2);
                        acc += (double) v / 32768.0;
                    } else if (bits == 32) {
                        float v = 0.0f;
                        std::memcpy(&v, raw.data() + idx * 4, 4);
                        acc += (double) v;
                    }
                }
                out.pcm[i] = (float) (acc / channels);
            }
            break;
        } else {
            std::fseek(f, (long) size, SEEK_CUR);
        }
    }
    std::fclose(f);
    return !out.pcm.empty();
}

}  // namespace

int main(int argc, char ** argv) {
    if (argc < 4) {
        std::printf("usage: host_test <llm.gguf> <asr.gguf> <wav...>\n");
        return 2;
    }
    const std::string llm_path = argv[1];
    const std::string asr_path = argv[2];

    int failures = 0;

    // ---- stage 1: ASR ----
    std::printf("\n=== ASR (transcribe.cpp) ===\n");
    voicepersona::AsrEngine asr;
    std::string err;
    if (!asr.load(asr_path, 4, err)) {
        std::printf("ASR LOAD FAILED: %s\n", err.c_str());
        ++failures;
    } else {
        std::printf("ASR info: %s\n", asr.info().c_str());
        for (int i = 3; i < argc; ++i) {
            Wav w;
            if (!read_wav(argv[i], w)) { ++failures; continue; }
            std::printf("  %s (%d Hz, %.2fs)\n", argv[i], w.sample_rate,
                        (double) w.pcm.size() / (double) w.sample_rate);
            const std::string text = asr.transcribe(w.pcm.data(), (int) w.pcm.size(), err);
            if (!err.empty()) { std::printf("    ERROR: %s\n", err.c_str()); ++failures; }
            else if (text.empty()) { std::printf("    EMPTY TRANSCRIPT\n"); ++failures; }
            else { std::printf("    -> %s\n", text.c_str()); }
        }
    }

    // ---- stage 2: LLM chat ----
    std::printf("\n=== LLM (ternary Bonsai 1.7B) ===\n");
    voicepersona::ChatEngine chat;
    voicepersona::GenParams gp;
    gp.n_predict = 96;
    gp.n_ctx     = 2048;
    gp.n_threads = 4;
    if (!chat.load(llm_path, gp, err)) {
        std::printf("LLM LOAD FAILED: %s\n", err.c_str());
        ++failures;
    } else {
        std::printf("LLM info: %s\n", chat.info().c_str());
        chat.set_system(
            "너는 사용자의 페르소나를 따르는 대화 상대다.\n"
            "말투: 반말, 짧게, 장난스럽게\n"
            "자주 쓰는 말: 진짜?, 뭐야 ㅋㅋ\n"
            "항상 한국어로 2문장 이내로 답한다.");
        for (const char * q : {"안녕! 오늘 뭐 했어?", "내일 뭐 할까?"}) {
            const std::string reply = chat.send(q, nullptr, err);
            if (!err.empty()) { std::printf("  ERROR: %s\n", err.c_str()); ++failures; }
            else if (reply.empty()) { std::printf("  EMPTY REPLY to '%s'\n", q); ++failures; }
            else {
                std::printf("  user: %s\n  bot : %s\n", q, reply.c_str());
                std::printf("  (raw: %s)\n", chat.last_raw().c_str());
            }
        }
        std::printf("  history=%d prompt_tokens=%d\n", chat.history_size(),
                    chat.last_prompt_tokens());
    }

    // ---- stage 3: persona extraction prompt ----
    std::printf("\n=== Persona extraction ===\n");
    if (chat.loaded()) {
        voicepersona::ChatEngine extractor;
        voicepersona::GenParams ep = gp;
        ep.n_predict = 220;
        ep.temp      = 0.3f;
        if (extractor.load(llm_path, ep, err)) {
            extractor.set_system(
                "너는 대화 녹취록을 분석해 화자의 페르소나를 추출한다. "
                "설명 없이 JSON만 출력한다: "
                "{\"name\":\"\",\"tone\":\"\",\"speech_style\":\"\",\"traits\":[],\"catchphrases\":[]}");
            const std::string sample =
                "녹취록:\n"
                "A: 야 너 어제 왜 안 나왔어\n"
                "B: 아 몰라 그냥 좀 피곤해서 ㅋㅋ 진짜 미안\n"
                "A: 다음엔 꼭 와라\n"
                "B: ㅇㅇ 알겠어 미안미안 다음엔 내가 쏠게";
            const std::string out = extractor.send(sample, nullptr, err);
            std::printf("  %s\n", err.empty() ? out.c_str() : err.c_str());
            if (out.empty()) ++failures;
        } else {
            std::printf("  extractor load failed: %s\n", err.c_str());
            ++failures;
        }
    }

    std::printf("\n=== RESULT: %s (%d failures) ===\n", failures == 0 ? "PASS" : "FAIL", failures);
    return failures == 0 ? 0 : 1;
}
