// sweep.cpp - 주어진 구간만 잘라 transcribe() 1회: 어느 길이에서 죽는지 찾음.
// usage: sweep <asr.gguf> <wav> <start_sec> <dur_sec>
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
    if (argc < 5) { std::printf("usage: sweep <gguf> <wav> <start_sec> <dur_sec>\n"); return 2; }
    const int start_s = std::atoi(argv[3]);
    const int dur_s   = std::atoi(argv[4]);

    Wav w;
    if (!read_wav(argv[2], w)) { std::printf("cannot read wav\n"); return 1; }
    const size_t total = w.pcm.size();
    const size_t b = std::min(total, (size_t) start_s * w.rate);
    const size_t e = std::min(total, b + (size_t) dur_s * w.rate);
    std::printf("[s] 구간 %zu-%zu s (%.1f s)  RSS=%ld KB\n",
                b / w.rate, e / w.rate, (double) (e - b) / w.rate, rss_kb());

    AsrEngine asr;
    std::string err;
    if (!asr.load(argv[1], 4, err)) { std::printf("load failed: %s\n", err.c_str()); return 1; }
    std::printf("[s] 모델 로드 RSS=%ld KB — transcribe 시작\n", rss_kb());

    const std::string text = asr.transcribe(w.pcm.data() + b, (int) (e - b), err);
    std::printf("[s] 완료 RSS=%ld KB truncated=%d chars=%zu\n",
                rss_kb(), asr.last_run_truncated() ? 1 : 0, text.size());
    // VmHWM = 실행 중 피크. 그래프 할당/spike 크기 비교용.
    long hwm = -1;
    if (FILE * f = std::fopen("/proc/self/status", "r")) {
        char line[256];
        while (std::fgets(line, sizeof(line), f)) {
            if (strncmp(line, "VmHWM:", 6) == 0) { hwm = atol(line + 6); break; }
        }
        std::fclose(f);
    }
    std::printf("[s] PEAK(VmHWM)=%ld KB (%.2f GB)\n", hwm, hwm / 1048576.0);
    std::printf("[s] text: %s\n", text.substr(0, 300).c_str());
    return 0;
}