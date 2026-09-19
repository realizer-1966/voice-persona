package com.voicepersona.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** One downloadable GGUF needed on device. */
data class ModelSpec(
    val id: String,
    val label: String,
    val url: String,
    val fileName: String,
    val approxBytes: Long,
    val languages: List<String> = emptyList(),
    /** Single-pass audio ceiling, from the model's own input contract. */
    val maxAudioSeconds: Int = 48,
    val note: String = "",
) {
    fun file(context: Context): File = File(modelsDir(context), fileName)
}

object ModelCatalog {
    private const val HF = "https://huggingface.co"

    // Ternary-Bonsai, official group-64 Q2_0 band. Both sizes are qwen3
    // architecture, so mainline llama.cpp runs them - no fork binaries needed.
    // The 4B is the default: at 1.7B the ternary quant loses too much of the
    // instruction following to hold a conversation.
    val llm4B = ModelSpec(
        id = "llm-bonsai-4b",
        label = "Ternary-Bonsai 4B (Q2_0)",
        url = "$HF/prism-ml/Ternary-Bonsai-4B-gguf/resolve/main/Ternary-Bonsai-4B-Q2_0_g64.gguf",
        fileName = "Ternary-Bonsai-4B-Q2_0_g64.gguf",
        approxBytes = 1_137_806_656L,
    )

    val llm1_7B = ModelSpec(
        id = "llm-bonsai-1.7b",
        label = "Ternary-Bonsai 1.7B (Q2_0, 빠름)",
        url = "$HF/prism-ml/Ternary-Bonsai-1.7B-gguf/resolve/main/Ternary-Bonsai-1.7B-Q2_0_g64.gguf",
        fileName = "Ternary-Bonsai-1.7B-Q2_0_g64.gguf",
        approxBytes = 490_163_968L,
    )

    val llmOptions = listOf(llm4B, llm1_7B)

    /** Default language model. */
    val llm = llm4B

    val asrKo = ModelSpec(
        id = "asr-moonshine-ko",
        label = "Moonshine Base 한국어",
        url = "$HF/handy-computer/moonshine-base-ko-gguf/resolve/main/moonshine-base-ko-Q8_0.gguf",
        fileName = "moonshine-base-ko-Q8_0.gguf",
        approxBytes = 77_476_480L,
        languages = listOf("ko"),
        maxAudioSeconds = 48,
        note = "77MB 초경량, 한국어 전용, 최대 48초",
    )

    val asrJa = ModelSpec(
        id = "asr-moonshine-ja",
        label = "Moonshine Base 日本語",
        url = "$HF/handy-computer/moonshine-base-ja-gguf/resolve/main/moonshine-base-ja-Q8_0.gguf",
        fileName = "moonshine-base-ja-Q8_0.gguf",
        approxBytes = 77_476_480L,
        languages = listOf("ja"),
        maxAudioSeconds = 48,
        note = "77MB 초경량, 일본어 전용, 최대 48초",
    )

    // Qwen3-ASR is an audio-LLM: it auto-detects across 30 languages and takes a
    // whole recording in one pass (65k-token decoder context), so long persona
    // recordings do not have to be chunked.
    val asrQwen3 = ModelSpec(
        id = "asr-qwen3-0.6b",
        label = "Qwen3-ASR 0.6B (30개 언어)",
        url = "$HF/handy-computer/Qwen3-ASR-0.6B-gguf/resolve/main/Qwen3-ASR-0.6B-Q4_K_M.gguf",
        fileName = "Qwen3-ASR-0.6B-Q4_K_M.gguf",
        approxBytes = 589_557_760L,
        languages = listOf("ko", "ja", "en"),
        maxAudioSeconds = 87 * 60,
        note = "한국어·일본어 자동 감지, 긴 녹음 한 번에 처리",
    )

    val speech = listOf(asrQwen3, asrKo, asrJa)

    val asrDefault = asrQwen3

    /** What the app needs on disk for the current model choices. */
    fun required(selectedLlm: ModelSpec, selectedAsr: ModelSpec): List<ModelSpec> =
        listOf(selectedLlm, selectedAsr)
}

fun modelsDir(context: Context): File =
    File(context.filesDir, "models").apply { if (!exists()) mkdirs() }

enum class ModelState { MISSING, PARTIAL, READY }

object ModelStore {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun state(context: Context, spec: ModelSpec): ModelState {
        val f = spec.file(context)
        if (!f.exists()) return ModelState.MISSING
        // HuggingFace redirects can leave a small HTML/error body behind.
        return if (f.length() >= spec.approxBytes * 95 / 100) ModelState.READY else ModelState.PARTIAL
    }

    fun existingBytes(context: Context, specs: List<ModelSpec>): Long =
        specs.filter { state(context, it) == ModelState.READY }.sumOf { it.approxBytes }

    /** Downloads to a temp name and renames, so a failed run never looks READY. */
    suspend fun download(
        context: Context,
        spec: ModelSpec,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val target = spec.file(context)
            if (state(context, spec) == ModelState.READY) return@withContext Result.success(target)
            val tmp = File(target.parentFile, "${target.name}.part")
            if (tmp.exists()) tmp.delete()

            val request = Request.Builder().url(spec.url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("HTTP ${response.code} for ${spec.label}")
                    )
                }
                val body = response.body ?: return@withContext Result.failure(
                    IllegalStateException("empty body for ${spec.label}")
                )
                val total = if (body.contentLength() > 0) body.contentLength() else spec.approxBytes
                body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 16)
                        var done = 0L
                        var lastTick = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            if (done - lastTick > 2_000_000L) {
                                lastTick = done
                                onProgress(done, total)
                            }
                        }
                        output.flush()
                    }
                }
                onProgress(total, total)
            }
            if (tmp.length() < spec.approxBytes * 95 / 100) {
                tmp.delete()
                return@withContext Result.failure(
                    IllegalStateException("다운로드가 불완전합니다 (${tmp.length()} bytes)")
                )
            }
            if (target.exists()) target.delete()
            val ok = tmp.renameTo(target)
            if (!ok) {
                return@withContext Result.failure(IllegalStateException("파일 이동 실패"))
            }
            Result.success(target)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
