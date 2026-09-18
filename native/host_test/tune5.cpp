// tune5.cpp - persona prompt formats: description vs real conversation few-shot.
#include "../voice_engine.h"

#include <cstdio>
#include <string>
#include <vector>

using voicepersona::ChatEngine;
using voicepersona::GenParams;
using voicepersona::Turn;

namespace {

void run(const char * label, const std::string & model, const std::string & system,
         const std::vector<Turn> & examples) {
    ChatEngine engine;
    GenParams gp;
    gp.n_predict = 128;
    gp.n_ctx     = 2048;
    gp.n_threads = 4;
    gp.temp      = 0.7f;
    std::string err;
    if (!engine.load(model, gp, err)) {
        std::printf("load failed: %s\n", err.c_str());
        return;
    }
    std::vector<Turn> history;
    history.push_back({"system", system});
    for (const Turn & t : examples) {
        history.push_back(t);
    }
    engine.set_history(history);

    const char * questions[] = {"안녕! 오늘 뭐 했어?", "내일 뭐 할까?", "나 오늘 좀 힘들었어"};
    std::printf("\n##### %s\n", label);
    for (const char * q : questions) {
        const std::string reply = engine.send(q, nullptr, err);
        std::printf("  Q: %s\n  A: %s\n", q, err.empty() ? reply.c_str() : err.c_str());
    }
}

}  // namespace

int main(int argc, char ** argv) {
    if (argc < 2) {
        std::printf("usage: tune5 <llm.gguf>\n");
        return 2;
    }
    const std::string model = argv[1];

    // L: description only, style stated in the third person.
    run("L description only",
        model,
        "너는 '지훈'이라는 친구와 대화하는 것처럼 답한다. 지훈은 반말을 쓰고 "
        "장난스럽게 말하며 문장을 짧게 끊는다. 답은 한국어 1~2문장.",
        {});

    // M: description plus real conversation turns pulled from a recording.
    run("M description + real-turn few-shot",
        model,
        "너는 '지훈'이라는 친구다. 반말로 짧고 장난스럽게 한국어로만 답한다. "
        "사용자가 한 말을 그대로 따라 말하지 않는다.",
        {
            {"user", "너 어제 왜 안 나왔어"},
            {"assistant", "아 몰라 그냥 좀 피곤해서 ㅋㅋ 미안"},
            {"user", "다음엔 꼭 와라"},
            {"assistant", "ㅇㅇ 알겠어 다음엔 내가 쏠게"},
        });

    return 0;
}
