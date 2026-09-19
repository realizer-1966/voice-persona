// mem_probe.cpp - 단계별 RSS 측정: 모델 로딩 후 단일 run()에서 죽는 원인 확인.
#include "../asr_engine.h"

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

using voicepersona::AsrEngine;

namespace {
struct Wav { std::vector<float> pcm; int rate = 16000; };

bool read_wav(const char * path, Wav & out) {
    FILE * f = std::fopen(path, "rb");
    if (!f) return false;
    char hdr[12];
    if (std::fread(hdr, 1, 12, f) != 12) { std::fclose(f); return false; }
    uint32_t size = 0;
    std::vector<char> data;
    char id[4];
    while (std::fread(id, 1, 4, f) == 4) {
        if (std::fread(&size, 4, 1, f) != 1) break;
        if (std::memcmp(id, "fmt ", 4) == 0) {
            std::vector<char> fmt(size);
            std::fread(fmt.data(), 1, size, f);
            uint32_t r = 0; std::memcpy(&r, fmt.data() + 4, 4);
            out.rate = (int) r;
        } else if (std::memcmp(id, "data", 4) == 0) {
            data.resize(size);
            std::fread(data.data(), 1, size, f);
            break;
        } else {
            std::fseek(f, (long) size + (size % 2), SEEK_CUR);
        }
    }
    std::fclose(f);
    const size_t n = data.size() / 2;
    out.pcm.resize(n);
    for (size_t i = 0; i < n; ++i) {
        int16_t v = 0; std::memcpy(&v, data.data() + i * 2, 2);
        out.pcm[i] = v / 32768.0f;
    }
    return !out.pcm.empty();
}

long rss_kb() {
    FILE * f = std::fopen("/proc/self/status", "r");
    if (!f) return -1;
    char line[256]; long v = -1;
    while (std::fgets(line, sizeof(line), f)) {
        if (strncmp(line, "VmRSS:", 6) == 0) { v = atol(line + 6); break; }
    }
    std::fclose(f);
    return v;
}
}  // namespace

int main(int argc, char ** argv) {
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    if (argc < 3) { std::printf("usage: mem_probe <asr.gguf> <long.wav>\n"); return 2; }
    std::printf("[m0] 시작 RSS=%ld KB\n", rss_kb());

    Wav w;
    if (!read_wav(argv[2], w)) { std::printf("cannot read wav\n"); return 1; }
    std::printf("[m1] wav 로드 RSS=%ld KB  (%.1f s, %zu samples, %zu MB pcm)\n",
                rss_kb(), (double) w.pcm.size() / w.rate, w.pcm.size(),
                w.pcm.size() * sizeof(float) / (1024 * 1024));

    AsrEngine asr;
    std::string err;
    if (!asr.load(argv[1], 4, err)) {
        std::printf("load failed: %s\n", err.c_str());
        return 1;
    }
    std::printf("[m2] 모델 로드 RSS=%ld KB  (한회 한계 %lld ms = %.1f s)\n",
                rss_kb(), asr.max_audio_ms(), asr.max_audio_ms() / 1000.0);
    std::printf("[m3] single run() 시작...\n");

    const std::string whole = asr.transcribe(w.pcm.data(), (int) w.pcm.size(), err);
    std::printf("[m4] run() 반환 RSS=%ld KB  err='%s' truncated=%d chars=%zu\n",
                rss_kb(), err.c_str(), asr.last_run_truncated() ? 1 : 0, whole.size());
    std::printf("=== 진단 완료 ===\n");
    return 0;
}
