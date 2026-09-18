package com.voicepersona.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns raw conversation transcripts into a [Persona] with the local 1.7B model.
 *
 * The model is small, so the job is split in two: one call drafts the persona
 * as JSON, and a deterministic fallback derives fields from the transcript when
 * the model's JSON does not survive parsing.
 */
class PersonaExtractor(private val llm: ChatLlm) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val instruction = """
        너는 대화 녹취록을 분석해 화자의 페르소나를 뽑아내는 도구다.
        반드시 아래 JSON 하나만 출력한다. 설명, 인사, 코드블록 기호는 쓰지 않는다.
        {"name":"","tone":"","speech_style":"","traits":[],"catchphrases":[],"background":"","relationship":""}
    """.trimIndent()

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

        onProgress("페르소나 초안 생성 중")
        var draftText = ""
        runCatching {
            llm.setSystem(instruction)
            draftText = llm.send("녹취록:\n$excerpt\n\n위 화자의 페르소나 JSON:")
        }.onFailure { draftText = "" }

        onProgress("요약 다듬는 중")
        var summary = ""
        runCatching {
            llm.setSystem(
                "너는 녹취록을 요약하는 도구다. 한국어로 2문장 이내로만 답한다."
            )
            summary = llm.send(
                "다음 대화의 화자 성격과 말투를 두 문장으로 요약해줘:\n" + excerpt.take(1200)
            )
        }.onFailure { summary = "" }

        val parsed = parseDraft(draftText)
        val fallback = heuristic(cleaned)

        val name = parsed.first("name").ifBlank { fallback.name }
        val tone = parsed.first("tone").ifBlank { summary.take(120).ifBlank { fallback.tone } }
        val style = parsed.first("speech_style").ifBlank { fallback.speechStyle }
        val traits = parsed.second("traits").ifEmpty { fallback.traits }
        val catchphrases = parsed.second("catchphrases").ifEmpty { fallback.catchphrases }
        val background = parsed.first("background").ifBlank { fallback.background }

        val examples = pickExampleTurns(cleaned)

        val id = "p" + System.currentTimeMillis().toString(36)
        return Persona(
            id = id,
            name = name.ifBlank { "페르소나 " + id.takeLast(4) },
            tone = tone,
            speechStyle = style,
            traits = traits.take(6),
            catchphrases = catchphrases.take(8),
            background = background,
            relationship = parsed.first("relationship"),
            sourceNote = sourceNote,
            transcript = cleaned,
            exampleUser = examples.map { it.first },
            exampleReply = examples.map { it.second },
        )
    }

    /**
     * Lifts short question/answer pairs out of the transcript so the chat model
     * can be given real conversation turns. Measured on Ternary-Bonsai: a few
     * real turns carry the style far better than a described one, while a
     * sample sentence quoted inside the system prompt just gets copied.
     */
    private fun pickExampleTurns(transcript: String): List<Pair<String, String>> {
        val lines = transcript.split('\n')
            .map { it.trim() }
            .filter { it.length in 3..60 }

        val turns = ArrayList<Pair<String, String>>()
        for (i in 0 until lines.size - 1) {
            val current = lines[i]
            var next = lines[i + 1]
            // Drop a leading speaker label like "B:" or "나:".
            next = next.replace(Regex("^[A-Za-z가-힣]{1,4}\\s*[:：]\\s*"), "")
            if (next.length !in 3..60) continue
            // The reply should look like a short utterance, not a question.
            if (next.endsWith("?") || next.endsWith("？")) continue
            turns.add(current to next)
            if (turns.size >= 3) break
        }
        return turns
    }

    /** Returns (single values, list values) recovered from the model output. */
    private fun parseDraft(raw: String): Pair<(String) -> String, (String) -> List<String>> {
        val singles = mutableMapOf<String, String>()
        val lists = mutableMapOf<String, List<String>>()

        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val candidate = raw.substring(start, end + 1)
            runCatching {
                val obj = json.parseToJsonElement(candidate) as JsonObject
                obj.forEach { (key, value) ->
                    when (value) {
                        is JsonArray -> lists[key] = value.mapNotNull { it.jsonPrimitive.contentOrNull }
                            .filter { it.isNotBlank() }
                        is JsonObject -> Unit
                        else -> {
                            val text = value.jsonPrimitive.contentOrNull ?: ""
                            if (text.isNotBlank()) singles[key] = text.trim()
                        }
                    }
                }
            }
        }
        // Tolerate half-broken JSON by scraping "key":"value" pairs.
        if (singles.isEmpty()) {
            val fieldRegex = Regex("\"([a-z_]+)\"\\s*:\\s*\"([^\"]{1,200})\"")
            fieldRegex.findAll(raw).forEach { m ->
                singles.putIfAbsent(m.groupValues[1], m.groupValues[2].trim())
            }
        }
        if (lists.isEmpty()) {
            val listRegex = Regex("\"([a-z_]+)\"\\s*:\\s*\\[([^\\]]{0,400})\\]")
            listRegex.findAll(raw).forEach { m ->
                val items = m.groupValues[2].split(',')
                    .map { it.trim().trim('"', '\'', ' ') }
                    .filter { it.isNotBlank() }
                if (items.isNotEmpty()) lists.putIfAbsent(m.groupValues[1], items)
            }
        }
        return Pair({ key -> singles[key] ?: "" }, { key -> lists[key] ?: emptyList() })
    }

    /** Language-only fallback that never fails, so a persona is always produced. */
    private fun heuristic(transcript: String): Persona {
        val sentences = transcript.split(Regex("[.!?。！？\\n]+"))
            .map { it.trim() }
            .filter { it.length in 2..80 }

        val phrases = mutableListOf<String>()
        val laughTokens = listOf("ㅋㅋ", "ㅎㅎ", "ㅠㅠ", "ㅇㅇ", "진짜", "완전", "그니까", "아니")
        for (token in laughTokens) {
            if (transcript.contains(token)) phrases.add(token)
        }
        // Short repeated fragments read as habitual expressions.
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
