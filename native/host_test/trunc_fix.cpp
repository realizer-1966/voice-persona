// trunc_fix.cpp - 앱 청킹 파이프라인을 호스트에서 그대로 검증한다.
// Models.kt와 동일 정책: 60 s 청크, 잘리면 절반으로 쪼개 재시도(depth<=4).
// usage: trunc_fix <asr.gguf> <long.wav> [chunk_sec]
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
    int channels = 1, bits = 16, rate = 16000;
    char id[4];
    uint32_t size = 0;
    std::vector<char> data;
    while (std::fread(id, 1, 4, f) == 4) {
        if (std::fread(&size, 4, 1, f) != 1) break;
        if (std::memcmp(id, "fmt ", 4) == 0) {
            std::vector<char> fmt(size);
            std::fread(fmt.data(), 1, size, f);
            uint16_t ch = 0, b = 0;
            uint32_t r = 0;
            std::memcpy(&ch, fmt.data() + 2, 2);
            std::memcpy(&r, fmt.data() + 4, 4);
            std::memcpy(&b, fmt.data() + 14, 2);
            channels = ch; rate = (int) r; bits = b;
        } else if (std::memcmp(id, "data", 4) == 0) {
            data.resize(size);
            std::fread(data.data(), 1, size, f);
            break;
        } else {
            std::fseek(f, (long) size + (size % 2), SEEK_CUR);
        }
    }
    std::fclose(f);
    if (data.empty()) return false;
    out.rate = rate;
    if (bits == 16) {
        const size_t n = data.size() / 2;
        out.pcm.resize(n);
        for (size_t i = 0; i < n; ++i) {
            int16_t v = 0;
            std::memcpy(&v, data.data() + i * 2, 2);
            out.pcm[i] = v / 32768.0f;
        }
    } else if (bits == 32) {
        const size_t n = data.size() / 4;
        out.pcm.resize(n);
        for (size_t i = 0; i < n; ++i) {
            float v = 0.0f;
            std::memcpy(&v, data.data() + i * 4, 4);
            out.pcm[i] = v;
        }
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

// SpeechToText.kt runChunkComplete와 동일: 잘리면 절반 재시도.
static std::string run_chunk_complete(AsrEngine & asr, const float * pcm, size_t n, size_t rate, int depth = 0) {
    std::string err;
    const std::string text = asr.transcribe(pcm, (int) n, err);
    if (!err.empty()) {
        std::printf("  ! ERR: %s\n", err.c_str());
        return "";
    }
    const bool trunc = asr.last_run_truncated();
    if (!trunc) return text;
    if (depth >= 4 || n < (size_t) rate * 20) {
        std::printf("  ! 잘림 회복 불가(depth=%d, %.1f s) — 부분 반환\n", depth, (double) n / rate);
        return text;
    }
    const size_t half = n / 2;
    std::printf("  ~ 잘림 감지: %.1f s -> 2x %.1f s 재시도 (depth %d)\n",
                (double) n / rate, (double) half / rate, depth);
    const std::string first  = run_chunk_complete(asr, pcm, half, rate, depth + 1);
    const std::string second = run_chunk_complete(asr, pcm + half, n - half, rate, depth + 1);
    return (first + " " + second);
}

int main(int argc, char ** argv) {
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    if (argc < 3) {
        std::printf("usage: trunc_fix <asr.gguf> <long.wav> [chunk_sec]\n");
        return 2;
    }
    const int chunk_sec = argc > 3 ? std::atoi(argv[3]) : 60;

    Wav w;
    if (!read_wav(argv[2], w)) {
        std::printf("cannot read wav\n");
        return 1;
    }
    const int total_sec = (int) (w.pcm.size() / w.rate);
    std::printf("audio: %.1f s (%zu samples) | chunk=%d s\n",
                (double) w.pcm.size() / w.rate, w.pcm.size(), chunk_sec);

    AsrEngine asr;
    std::string err;
    if (!asr.load(argv[1], 4, err)) {
        std::printf("load failed: %s\n", err.c_str());
        return 1;
    }
    std::printf("model loaded RSS=%ld KB\n", rss_kb());

    const size_t chunk = (size_t) chunk_sec * w.rate;
    std::string combined;
    int parts = 0;
    int retried = 0;
    for (size_t start = 0; start < w.pcm.size(); start += chunk) {
        const size_t end = std::min(w.pcm.size(), start + chunk);
        if (end - start < w.rate / 2) break;   // 0.5 s 미만 꼬리 버림(앱 동일)
        std::string piece = run_chunk_complete(asr, w.pcm.data() + start, end - start, w.rate);
        if (asr.last_run_truncated()) ++retried;   // 재시도 경로를 밟았다는 표시
        std::printf("[%d] %zu-%zu s | truncated=%s | chars=%zu\n", ++parts,
                    start / w.rate, end / w.rate,
                    asr.last_run_truncated() ? "TRUE" : "false", piece.size());
        if (!piece.empty()) {
            combined += piece;
            combined += " ";
        }
    }
    std::printf("\nchunks=%d retried=%d total_chars=%zu\n", parts, retried, combined.size());
    std::printf("RESULT: %s\n", parts > 1 && !combined.empty()
                                  ? "PASS (청킹으로 전체 전사 완료)"
                                  : "INCONCLUSIVE");
    std::printf("--- transcript ---\n%s\n", combined.substr(0, 2000).c_str());
    return 0;
}