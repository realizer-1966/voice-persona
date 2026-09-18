// tune4.cpp - verify thinking-off + echo-strip + paragraph stop.
#include "../voice_engine.h"

#include <cstdio>
#include <string>

using voicepersona::ChatEngine;
using voicepersona::GenParams;

int main(int argc, char ** argv) {
    if (argc < 2) {
        std::printf("usage: tune4 <llm.gguf>\n");
        return 2;
    }

    const char * persona =
        "너는 '지훈'이라는 인물인 것처럼 사용자와 대화한다.\n"
        "말투: 반말, 장난스럽게\n"
        "이 사람이 실제로 한 말 예시: \"아 몰라 그냥 좀 피곤해서 ㅋㅋ\", \"ㅇㅇ 알겠어 미안미안\"\n"
        "규칙: 사용자가 한 말을 그대로 따라 말하지 않는다. "
        "생각 과정을 출력하지 않는다. 한국어 1~2문장만 답한다.";

    const char * plain =
        "너는 사용자의 말에 자연스럽게 반응하는 한국어 대화 상대다. "
        "사용자가 한 말을 그대로 따라 말하지 않고, 1~2문장으로만 답한다.";

    struct Case { const char * label; const char * sys; };
    const Case cases[] = {
        {"J thinking-off / plain", plain},
        {"K thinking-off / persona", persona},
    };
    const char * questions[] = {"안녕! 오늘 뭐 했어?", "내일 뭐 할까?", "나 오늘 좀 힘들었어"};

    for (const Case & c : cases) {
        ChatEngine engine;
        GenParams gp;
        gp.n_predict = 128;
        gp.n_ctx     = 2048;
        gp.n_threads = 4;
        gp.temp      = 0.7f;
        std::string err;
        if (!engine.load(argv[1], gp, err)) {
            std::printf("load failed: %s\n", err.c_str());
            return 1;
        }
        engine.set_system(c.sys);
        std::printf("\n##### %s\n", c.label);
        for (const char * q : questions) {
            const std::string reply = engine.send(q, nullptr, err);
            std::printf("  Q: %s\n", q);
            std::printf("  A: %s\n", err.empty() ? reply.c_str() : err.c_str());
        }
    }
    return 0;
}
