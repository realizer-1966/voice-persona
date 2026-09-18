# Voice Persona (보이스 페르소나)

음성으로 대화하는 안드로이드 앱. 모든 추론이 **기기 안에서** 실행된다.

- **대화 모델**: `Ternary-Bonsai` (PrismML 삼진/1.58bit GGUF, qwen3 아키텍처)
- **음성 인식**: [transcribe.cpp](https://github.com/handy-computer/transcribe.cpp) + Moonshine base (한국어 / 日本語)
- **페르소나**: 기존 대화 녹음 파일을 넣으면 음성을 글로 옮기고, 그 내용에서 말투·성격·자주 쓰는 표현을 뽑아 페르소나를 만든다
- **빌드**: GitHub Actions 에서 APK 생성

## 구성

```
native/                     JNI 없이 데스크톱에서 검증 가능한 C++ 엔진
  voice_engine.{h,cpp}      llama.cpp 기반 채팅 엔진 (KV 재사용, 사고 블록 제거, echo 제거)
  asr_engine.{h,cpp}        transcribe.cpp 기반 음성 인식
  host_test/                실제 모델로 엔진을 검증하는 데스크톱 하네스
Android/src/
  llm/                      llama.cpp 네이티브 모듈 (FetchContent 로 upstream 포크 고정)
  asr/                      transcribe.cpp 네이티브 모듈
  app/                      Compose UI, 페르소나 저장, 모델 다운로드, 마이크/TTS
```

## 왜 네이티브 모듈이 두 개인가

`llama.cpp` 와 `transcribe.cpp` 는 각각 **자체 ggml 사본**을 번들한다. 한 CMake 프로젝트에서
두 벌을 빌드하면 타깃 이름(`ggml`, `ggml-base`, `ggml-cpu`)이 충돌한다. 그래서 라이브러리
모듈을 둘로 나눠 각자 독립된 CMake 트리에서 빌드하고, 앱이 둘 다 링크한다.

## 모델

| 용도 | 파일 | 크기 | 비고 |
|---|---|---|---|
| 대화 | `Ternary-Bonsai-4B-Q2_0_g64.gguf` | 1.14 GB | 기본값, 한국어 대화 품질 확보 |
| 대화 (경량) | `Ternary-Bonsai-1.7B-Q2_0_g64.gguf` | 490 MB | 빠르지만 지시 이행이 약함 |
| 음성 (한국어) | `moonshine-base-ko-Q8_0.gguf` | 77 MB | 1회 최대 48초 |
| 음성 (일본어) | `moonshine-base-ja-Q8_0.gguf` | 77 MB | |

공식 group-64 `Q2_0` 대역을 쓴다. 이 대역은 mainline llama.cpp 로 읽히므로 포크 바이너리가
필요 없다 (`PQ2_0`/`PTQ1_0` 는 PrismML 포크 전용).

모델은 앱에서 HuggingFace 로부터 한 번만 내려받아 `filesDir/models` 에 저장한다. 이후에는
오프라인으로 동작한다.

## 실측으로 정한 기본값

데스크톱 하네스(`native/host_test`)로 실제 모델을 돌려 확인한 내용:

- **사고 블록**: qwen3 계열이라 기본적으로 ` thinking` 블록을 연다. 프롬프트에서 블록을 미리
  닫아 두면(`enable_thinking=false` 렌더링) 추론에 예산을 낭비하지 않는다.
- **페르소나 표현 방식**: 시스템 프롬프트에 예시 문장을 *인용*하면 모델이 그 문장을 그대로
  복사한다. 대신 녹취록에서 뽑은 **실제 대화 턴**을 few-shot 으로 넣으면 말투가 훨씬 잘 전이된다.
- **답변 길이**: 1.7B/4B 모두 첫 문단을 넘기면 반복 루프에 빠진다. 첫 빈 줄에서 생성을 멈춘다.
- **온도**: 0.2 는 품질이 떨어지고 0.7 이 안정적이다.

## 빌드

`main` 에 push 하면 `.github/workflows/build_android.yaml` 이 APK 를 만든다.

```bash
./gradlew :app:assembleRelease
```

arm64-v8a 전용. NDK 29, CMake 3.31.6, JDK 21.

## 데스크톱 검증

```bash
# 호스트용 llama.cpp 와 transcribe.cpp 를 먼저 빌드한 뒤
g++ -O2 -std=c++17 -o host_test/host_test host_test/main.cpp voice_engine.cpp asr_engine.cpp \
  -I<llama.cpp>/include -I<transcribe.cpp>/include \
  -L<llama.cpp>/build/bin -lllama \
  -L<transcribe.cpp>/build/src -L<transcribe.cpp>/build/ggml/src \
  -ltranscribe -lggml -lggml-cpu -lggml-base -lpthread -ldl -lm
./host_test/host_test <llm.gguf> <asr.gguf> <audio.wav>
```

## 권한

- `RECORD_AUDIO` — 음성 대화
- `INTERNET` — 모델 최초 다운로드만
