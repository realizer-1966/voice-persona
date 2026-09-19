package com.voicepersona.app

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Persona(
    val id: String,
    val name: String = "",
    val tone: String = "",
    val speechStyle: String = "",
    val traits: List<String> = emptyList(),
    val catchphrases: List<String> = emptyList(),
    val background: String = "",
    val relationship: String = "",
    val sourceNote: String = "",
    val transcript: String = "",
    /** Short exchanges lifted from the recording, used as chat examples. */
    val exampleUser: List<String> = emptyList(),
    val exampleReply: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
) {
    /**
     * System prompt for the small local model. Deliberately a short description:
     * measured behaviour is that quoting sample sentences here makes the model
     * copy them verbatim, so real examples go in as chat turns instead.
     */
    fun systemPrompt(): String {
        val sb = StringBuilder()
        sb.append("너는 '").append(name.ifBlank { "친구" }).append("'라는 사람이다. ")
        if (tone.isNotBlank()) sb.append(tone).append(". ")
        if (speechStyle.isNotBlank()) sb.append(speechStyle).append(". ")
        if (traits.isNotEmpty()) sb.append(traits.joinToString(", ")).append(". ")
        if (background.isNotBlank()) sb.append("배경: ").append(background).append(". ")
        if (relationship.isNotBlank()) sb.append("사용자와의 관계: ").append(relationship).append(". ")
        sb.append("짧고 자연스럽게 한국어로만 답한다.")
        return sb.toString()
    }

    fun examples(): List<Pair<String, String>> =
        exampleUser.zip(exampleReply).filter { it.first.isNotBlank() && it.second.isNotBlank() }

    fun summaryLine(): String {
        val bits = listOfNotNull(
            tone.takeIf { it.isNotBlank() },
            traits.firstOrNull(),
        )
        return if (bits.isEmpty()) "설명 없음" else bits.joinToString(" · ")
    }
}

/**
 * Personas live as JSON files under filesDir/personas. A tiny local store keeps
 * everything offline - no account, no server.
 */
object PersonaStore {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private fun dir(context: Context): File =
        File(context.filesDir, "personas").apply { if (!exists()) mkdirs() }

    suspend fun list(context: Context): List<Persona> = withContext(Dispatchers.IO) {
        val files = dir(context).listFiles { f -> f.name.endsWith(".json") } ?: return@withContext emptyList()
        files.mapNotNull { f ->
            runCatching { json.decodeFromString<Persona>(f.readText()) }.getOrNull()
        }.sortedByDescending { it.createdAt }
    }

    suspend fun save(context: Context, persona: Persona) = withContext(Dispatchers.IO) {
        val safeId = persona.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        File(dir(context), "$safeId.json").writeText(json.encodeToString(persona))
        Unit
    }

    suspend fun delete(context: Context, id: String) = withContext(Dispatchers.IO) {
        val safeId = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        File(dir(context), "$safeId.json").delete()
        Unit
    }

    suspend fun find(context: Context, id: String?): Persona? {
        if (id == null) return null
        return list(context).firstOrNull { it.id == id }
    }
}

/** Very small key/value store for the app's own settings. */
object AppPrefs {
    private const val FILE = "voicepersona.prefs"
    private const val KEY_ACTIVE_PERSONA = "active_persona"
    private const val KEY_TTS_ENABLED = "tts_enabled"

    private fun read(context: Context): MutableMap<String, String> {
        val f = File(context.filesDir, FILE)
        val map = mutableMapOf<String, String>()
        if (!f.exists()) return map
        f.readLines().forEach { line ->
            val idx = line.indexOf('=')
            if (idx > 0) {
                map[line.substring(0, idx)] = String(
                    Base64.decode(line.substring(idx + 1), Base64.NO_WRAP)
                )
            }
        }
        return map
    }

    private fun write(context: Context, map: Map<String, String>) {
        val text = map.entries.joinToString("\n") { (k, v) ->
            "$k=" + Base64.encodeToString(v.toByteArray(), Base64.NO_WRAP)
        }
        File(context.filesDir, FILE).writeText(text)
    }

    fun set(context: Context, key: String, value: String) {
        val map = read(context)
        map[key] = value
        write(context, map)
    }

    fun get(context: Context, key: String, default: String = ""): String =
        read(context)[key] ?: default

    fun activePersona(context: Context): String? =
        get(context, KEY_ACTIVE_PERSONA).takeIf { it.isNotBlank() }

    fun setActivePersona(context: Context, id: String?) =
        set(context, KEY_ACTIVE_PERSONA, id ?: "")

    fun ttsEnabled(context: Context): Boolean = get(context, KEY_TTS_ENABLED, "1") == "1"

    fun setTtsEnabled(context: Context, value: Boolean) = set(context, KEY_TTS_ENABLED, if (value) "1" else "0")
}
