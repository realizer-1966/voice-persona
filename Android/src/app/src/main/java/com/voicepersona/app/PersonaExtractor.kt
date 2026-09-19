package com.voicepersona.app

import kotlinx.serialization.Serializable

/**
 * Turns raw conversation transcripts into a [Persona] with the local model.
 *
 * Everything here is shaped by measurements on Ternary-Bonsai 4B:
 *
 *  - Asking for a JSON object whose empty schema appears in the prompt makes the
 *    model echo that blank schema back, so no field is ever filled. Each field is
 *    therefore its own short question with a one-line answer.
 *  - ASR output is a single paragraph: no newlines and no speaker labels, so
 *    exchanges are recovered by splitting sentences, not lines.
 *  - Real question/answer turns taken from the recording transfer style far
 *    better than sentences quoted in the system prompt (quoted text is copied).
 *
 * When the model answers badly the result is still a usable persona: the
 * transcript, the example turns and the deterministic fallback carry it.
 */
class PersonaExtractor(private val llm: ChatLlm) {

    private val questionTone =
        "녹취록 화자의 말투를 한 줄로만 답한다. 예: 반말, 문장이 짧고 장난스러움"
    private val questionTraits =
        "녹취록 화자의 성격을 쉼표로 구분해 3개만 답한다."
    private val questionPhrases =
        "녹취록에 실제로 반복해 나온 표현을 쉼표로 3개만 답한다. 없으면 '없음'이라고 답한다."
    private val questionBackground =
        "녹취록만 보고 화자에 대해 추측되는 배경 한 줄만 답한다. 모르면 '없음'이라고 답한다."

    suspend fun extract(
        transcript: String,
        sourceNote: String,
        onProgress: (String) -> Unit = {},
    ): Persona {
        onProgress("녹취록을 정리하는 중")
        val cleaned = transcript.trim()
        require(cleaned.isNotEmpty()) { "녹취록이 비어 있습니다" }

        // Keep the prompt inside the small model's comfort zone.
        val excerpt = if (cleaned.length > 2400) cleaned.take(2400) else cleaned

        onProgress("말투 분석 중")
        val tone = ask(questionTone, excerpt)

        onProgress("성격 분석 중")
        val traits = splitList(ask(questionTraits, excerpt))

        onProgress("자주 쓰는 표현 찾는 중")
        val phrases = splitList(ask(questionPhrases, excerpt)).filterNot { it.contains("없") }

        onProgress("배경 정리 중")
        val background = ask(questionBackground, excerpt).let {
            if (it.contains("없음") || it.contains("없다")) "" else it
        }

        onProgress("예시 대화 뽑는 중")
        // Examples help when the recording is a real exchange, but on narration
        // the model copies whole sentences out of them (measured). So they are
        // only attached when the transcript actually looks conversational.
        val conversational = looksConversational(cleaned)
        val examples = if (conversational) pickExampleTurns(cleaned) else emptyList()
        val fallback = heuristic(cleaned)

        val id = "p" + System.currentTimeMillis().toString(36)
        return Persona(
            id = id,
            name = "페르소나 " + id.takeLast(4),
            // A blank model answer falls back to what the transcript itself shows.
            tone = tone.ifBlank { fallback.tone },
            speechStyle = fallback.speechStyle,
            traits = (if (traits.isEmpty()) fallback.traits else traits).take(6),
            catchphrases = (if (phrases.isEmpty()) fallback.catchphrases else phrases).take(8),
            background = background,
            relationship = "",
            sourceNote = sourceNote,
            transcript = cleaned,
            exampleUser = examples.map { it.first },
            exampleReply = examples.map { it.second },
            conversationalSource = conversational,
        )
    }

    /**
     * Heuristic for "is this a conversation rather than a monologue or a read
     * passage": short sentences plus a question somewhere.
     */
    private fun looksConversational(transcript: String): Boolean {
        val sentences = transcript.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (sentences.size < 3) return false
        val short = sentences.count { it.length <= 45 }
        val hasQuestion = transcript.contains("?") || transcript.contains("？")
        return hasQuestion && short >= sentences.size / 2
    }

    /** One question, one short answer. Returns "" when the model fails. */
    private suspend fun ask(question: String, excerpt: String): String {
        var answer = ""
        runCatching {
            llm.setSystem("너는 녹취록을 분석한다. 물음에만 짧게 답한다.")
            answer = llm.send("$question\n\n녹취록:\n$excerpt")
        }
        return answer.trim().lines().firstOrNull().orEmpty().trim()
    }

    private fun splitList(text: String): List<String> =
        text.split(',', '，', '·', '\n')
            .map { it.trim().trim('-', '*', '"', '\'') }
            .filter { it.isNotBlank() && it.length <= 30 }

    /**
     * Lifts question/answer pairs out of the transcript so the chat model gets
     * real conversation turns. ASR output has no line breaks and no speaker
     * labels, so sentences are the unit here.
     */
    private fun pickExampleTurns(transcript: String): List<Pair<String, String>> {
        val sentences = transcript
            .split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.length in 4..60 }

        val turns = ArrayList<Pair<String, String>>()
        for (i in 0 until sentences.size - 1) {
            val current = sentences[i]
            val next = sentences[i + 1]
            // A statement closing a question reads as a natural exchange.
            if (next.endsWith("?") || next.endsWith("？")) continue
            turns.add(current to next)
            if (turns.size >= 3) break
        }
        return turns
    }

    /** Language-only fallback that never fails, so a persona is always produced. */
    private fun heuristic(transcript: String): Persona {
        val sentences = transcript.split(Regex("[.!?。！？\n]+"))
            .map { it.trim() }
            .filter { it.length in 2..80 }

        val phrases = mutableListOf<String>()
        val habitual = listOf("ㅋㅋ", "ㅎㅎ", "ㅠㅠ", "ㅇㅇ", "진짜", "완전", "그니까", "아니")
        for (token in habitual) {
            if (transcript.contains(token)) phrases.add(token)
        }
        sentences.groupingBy { it }.eachCount()
            .filter { (s, n) -> n >= 2 && s.length <= 12 }
            .keys
            .take(4)
            .forEach { if (!phrases.contains(it)) phrases.add(it) }

        val politeness = when {
            transcript.contains("습니다") || transcript.contains("세요") -> "존댓말"
            transcript.contains("해요") -> "부드러운 존댓말"
            else -> "반말"
        }
        val lengthStyle = when {
            sentences.isEmpty() -> "짧은 문장"
            sentences.map { it.length }.average() < 20 -> "짧게 끊어 말함"
            else -> "길게 설명함"
        }
        val traits = mutableListOf<String>()
        if (transcript.contains("ㅋㅋ") || transcript.contains("ㅎㅎ")) traits.add("장난스러움")
        if (transcript.contains("미안")) traits.add("잘 사과함")
        if (transcript.contains("?")) traits.add("질문을 많이 함")
        if (transcript.contains("같이") || transcript.contains("우리")) traits.add("함께하는 걸 좋아함")

        return Persona(
            id = "",
            name = "",
            tone = "$politeness · $lengthStyle",
            speechStyle = lengthStyle,
            traits = traits,
            catchphrases = phrases,
            background = "",
        )
    }
}
