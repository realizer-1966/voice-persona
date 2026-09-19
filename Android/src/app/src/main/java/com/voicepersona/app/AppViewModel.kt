package com.voicepersona.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class ModelReadiness { NONE, LLM_ONLY, ASR_ONLY, PARTIAL, READY }

data class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val pending: Boolean = false,
)

data class UiState(
    val modelsReady: Int = 0,
    val modelsTotal: Int = 0,
    val selectedLlmId: String = ModelCatalog.llm.id,
    val selectedAsrId: String = ModelCatalog.asrDefault.id,
    val requiredModels: List<ModelSpec> =
        ModelCatalog.required(ModelCatalog.llm, ModelCatalog.asrDefault),
    /** Auto language detection is a property of the loaded STT model. */
    val asrAutoDetect: Boolean = false,
    val downloading: ModelSpec? = null,
    val downloadProgress: Float = 0f,
    val llmInfo: String = "",
    val asrInfo: String = "",
    val asrNote: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val status: String = "",
    val busy: Boolean = false,
    val recording: Boolean = false,
    val ttsEnabled: Boolean = true,
    val personas: List<Persona> = emptyList(),
    val activePersonaId: String? = null,
    val personaBusy: Boolean = false,
    val personaProgress: String = "",
    val extractedDraft: Persona? = null,
    val permissionNeeded: Boolean = false,
) {
    val modelsOk: Boolean get() = modelsTotal > 0 && modelsReady == modelsTotal
    val activePersona: Persona? get() = personas.firstOrNull { it.id == activePersonaId }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val llm = ChatLlm()
    private val stt = SpeechToText()
    private var recorder: MicRecorder? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var msgId = 0L

    init {
        refreshModels()
        viewModelScope.launch {
            val personas = PersonaStore.list(getApplication())
            val active = AppPrefs.activePersona(getApplication())
            _state.update {
                it.copy(
                    personas = personas,
                    activePersonaId = active?.takeIf { id -> personas.any { p -> p.id == id } },
                    ttsEnabled = AppPrefs.ttsEnabled(getApplication()),
                )
            }
            if (_state.value.modelsOk) loadEngines()
        }
    }

    // ---------------------------------------------------------------- models

    var llmModelId: String
        get() = AppPrefs.get(getApplication(), "llm_model", ModelCatalog.llm.id)
        set(value) {
            AppPrefs.set(getApplication(), "llm_model", value)
        }

    private fun llmSpec(): ModelSpec = ModelCatalog.llmOptions
        .firstOrNull { it.id == llmModelId } ?: ModelCatalog.llm

    var asrModelId: String
        get() = AppPrefs.get(getApplication(), "asr_model", ModelCatalog.asrDefault.id)
        set(value) {
            AppPrefs.set(getApplication(), "asr_model", value)
        }

    private fun asrSpec(): ModelSpec = ModelCatalog.speech
        .firstOrNull { it.id == asrModelId } ?: ModelCatalog.asrDefault

    fun setLlmModel(id: String) {
        if (id == llmModelId) return
        llmModelId = id
        viewModelScope.launch {
            _state.update { it.copy(status = "언어 모델 교체 중") }
            runCatching { llm.unload() }
            refreshModels()
            loadEngines()
        }
    }

    fun refreshModels() {
        val context = getApplication<Application>()
        val need = ModelCatalog.required(llmSpec(), asrSpec())
        val ready = need.count { ModelStore.state(context, it) == ModelState.READY }
        _state.update {
            it.copy(
                modelsReady = ready,
                modelsTotal = need.size,
                selectedLlmId = llmModelId,
                selectedAsrId = asrModelId,
                asrNote = asrSpec().note,
                asrAutoDetect = asrSpec().languages.size > 2,
            )
        }
    }

    fun downloadModels() {
        if (_state.value.busy || _state.value.downloading != null) return
        viewModelScope.launch {
            val context = getApplication<Application>()
            // Only the selected LLM is fetched, so a 4B choice does not drag 1.7B in.
            val wanted = ModelCatalog.required(llmSpec(), asrSpec())
            for (spec in wanted) {
                if (ModelStore.state(context, spec) == ModelState.READY) continue
                _state.update {
                    it.copy(downloading = spec, downloadProgress = 0f, status = "${spec.label} 내려받는 중")
                }
                val result = ModelStore.download(context, spec) { done, total ->
                    val pct = if (total > 0) done.toFloat() / total.toFloat() else 0f
                    _state.update { it.copy(downloadProgress = pct.coerceIn(0f, 1f)) }
                }
                result.onFailure { error ->
                    _state.update {
                        it.copy(downloading = null, status = "다운로드 실패: ${error.message}")
                    }
                    refreshModels()
                    return@launch
                }
            }
            val need = ModelCatalog.required(llmSpec(), asrSpec())
            val ready = need.count { ModelStore.state(context, it) == ModelState.READY }
            _state.update {
                it.copy(downloading = null, modelsReady = ready, modelsTotal = need.size,
                        status = "모델 준비 완료")
            }
            loadEngines()
        }
    }

    fun loadEngines() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val spec = llmSpec()
            if (ModelStore.state(context, spec) != ModelState.READY) return@launch
            if (llm.isLoaded()) return@launch
            _state.update { it.copy(busy = true, status = "${spec.label} 로드 중") }
            runCatching {
                val info = llm.load(
                    spec.file(context),
                    nThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6),
                )
                _state.update { it.copy(llmInfo = info) }
            }.onFailure { error ->
                _state.update { it.copy(status = "언어 모델 로드 실패: ${error.message}") }
            }
            _state.update { it.copy(busy = false, status = "") }
            applyActivePersona(force = true)
            loadAsr()
        }
    }

    private suspend fun asrModel(context: Application): ModelSpec? {
        val spec = asrSpec()
        return spec.takeIf { ModelStore.state(context, it) == ModelState.READY }
    }

    fun loadAsr() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val spec = asrModel(context) ?: return@launch
            _state.update { it.copy(busy = true, status = "음성 인식 모델 로드 중") }
            stt.unload()
            runCatching {
                val info = stt.load(
                    spec.file(context),
                    nThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6),
                    maxAudioSeconds = spec.maxAudioSeconds,
                )
                _state.update {
                    it.copy(
                        asrInfo = "$info (${spec.label})",
                        asrNote = spec.note,
                        asrAutoDetect = spec.languages.size > 2,
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(status = "음성 모델 로드 실패: ${error.message}") }
            }
            _state.update { it.copy(busy = false, status = "") }
        }
    }

    /** Switches the STT model. Auto-detecting models ignore the language hint. */
    fun setAsrModel(id: String) {
        if (id == asrModelId) return
        asrModelId = id
        val spec = asrSpec()
        _state.update {
            it.copy(
                selectedAsrId = id,
                asrNote = spec.note,
                asrAutoDetect = spec.languages.size > 2,
                asrInfo = "",
            )
        }
        viewModelScope.launch {
            runCatching { stt.unload() }
            refreshModels()
            loadAsr()
        }
    }

    fun setTtsEnabled(enabled: Boolean) {
        AppPrefs.setTtsEnabled(getApplication(), enabled)
        _state.update { it.copy(ttsEnabled = enabled) }
    }

    // ------------------------------------------------------------------ chat

    private fun pushMessage(fromUser: Boolean, text: String, pending: Boolean = false): Long {
        val id = ++msgId
        _state.update { it.copy(messages = it.messages + ChatMessage(id, fromUser, text, pending)) }
        return id
    }

    private fun replaceMessage(id: Long, text: String, pending: Boolean = false) {
        _state.update { st ->
            st.copy(messages = st.messages.map {
                if (it.id == id) it.copy(text = text, pending = pending) else it
            })
        }
    }

    fun sendText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.busy) return
        pushMessage(fromUser = true, text = trimmed)
        viewModelScope.launch {
            _state.update { it.copy(busy = true, status = "생각 중") }
            val replyId = pushMessage(fromUser = false, text = "", pending = true)
            val buffer = StringBuilder()
            runCatching {
                llm.send(trimmed) { piece ->
                    buffer.append(piece)
                    replaceMessage(replyId, buffer.toString(), pending = true)
                    true
                }
            }.onSuccess { final ->
                val text2 = final.ifBlank { buffer.toString() }
                replaceMessage(replyId, if (text2.isBlank()) "(빈 응답)" else text2)
                if (_state.value.ttsEnabled) speak(text2)
            }.onFailure { error ->
                replaceMessage(replyId, "오류: ${error.message}")
            }
            _state.update { it.copy(busy = false, status = "") }
        }
    }

    private var speaker: Speaker? = null

    private fun speak(text: String) {
        val engine = speaker ?: Speaker(getApplication()).also { speaker = it }
        engine.speak(text)
    }

    fun stopSpeaking() {
        speaker?.stop()
    }

    // --------------------------------------------------------------- recording

    /** Toggles capture. Asks for the microphone grant when it is still missing. */
    fun toggleRecording() {
        val context = getApplication<Application>()
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            _state.update { it.copy(permissionNeeded = true) }
            return
        }
        if (_state.value.recording) {
            stopRecordingAndTranscribe()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        runCatching {
            val rec = MicRecorder()
            rec.start()
            recorder = rec
            _state.update { it.copy(recording = true, status = "듣는 중") }
        }.onFailure { error ->
            _state.update { it.copy(recording = false, status = "녹음 실패: ${error.message}") }
        }
    }

    private fun stopRecordingAndTranscribe() {
        val rec = recorder ?: return
        recorder = null
        val pcm = rec.stop()
        _state.update { it.copy(recording = false) }
        if (pcm.size < SpeechToText.SAMPLE_RATE / 2) {
            _state.update { it.copy(status = "너무 짧은 녹음입니다") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, status = "음성 인식 중") }
            runCatching { stt.transcribe(pcm) }
                .onSuccess { text ->
                    _state.update { it.copy(busy = false, status = "") }
                    if (text.isBlank()) {
                        pushMessage(fromUser = true, text = "(인식된 내용 없음)")
                    } else {
                        sendText(text)
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(busy = false, status = "인식 실패: ${error.message}") }
                }
        }
    }

    // --------------------------------------------------------------- personas

    fun refreshPersonas() {
        viewModelScope.launch {
            val personas = PersonaStore.list(getApplication())
            _state.update { it.copy(personas = personas) }
        }
    }

    fun setActivePersona(id: String?) {
        AppPrefs.setActivePersona(getApplication(), id)
        _state.update { it.copy(activePersonaId = id) }
        applyActivePersona(force = true)
    }

    private fun applyActivePersona(force: Boolean) {
        viewModelScope.launch {
            val persona = PersonaStore.find(getApplication(), _state.value.activePersonaId)
            if (!llm.isLoaded()) return@launch
            if (!force) return@launch
            val prompt = persona?.systemPrompt()
                ?: "너는 사용자의 말에 자연스럽게 반응하는 한국어 대화 상대다. 짧게 답한다."
            // Persona style travels as real chat turns, not as quoted text.
            llm.setContext(prompt, persona?.examples() ?: emptyList())
        }
    }

    /** Transcribes a user-picked recording, then drafts a persona from it. */
    fun buildPersonaFromAudio(uri: Uri, label: String) {
        if (_state.value.personaBusy) return
        viewModelScope.launch {
            _state.update {
                it.copy(personaBusy = true, personaProgress = "녹음 파일 여는 중", status = "")
            }
            try {
                val pcm = withContext(Dispatchers.IO) {
                    AudioDecoder.decode(getApplication(), uri)
                }
                val seconds = pcm.size / SpeechToText.SAMPLE_RATE
                if (pcm.size < SpeechToText.SAMPLE_RATE) {
                    throw IllegalStateException("오디오가 너무 짧습니다")
                }
                val spec = asrSpec()
                _state.update {
                    it.copy(
                        personaProgress = buildString {
                            append("음성 인식 중 (")
                            append(seconds)
                            append("초")
                            if (seconds > spec.maxAudioSeconds) {
                                // Chunked automatically, but say so, because the
                                // chunks are transcribed independently.
                                append(", ")
                                append(spec.label)
                                append(" 한도 ")
                                append(spec.maxAudioSeconds)
                                append("초 → 나눠서 처리")
                            }
                            append(")")
                        }
                    )
                }
                val transcript = stt.transcribe(pcm)
                if (transcript.isBlank()) throw IllegalStateException("인식된 텍스트가 없습니다")

                val extractor = PersonaExtractor(llm)
                val persona = extractor.extract(transcript, label) { step ->
                    _state.update { it.copy(personaProgress = step) }
                }
                _state.update { it.copy(extractedDraft = persona, personaProgress = "초안 완성") }
            } catch (t: Throwable) {
                _state.update { it.copy(personaProgress = "실패: ${t.message}") }
            } finally {
                _state.update { it.copy(personaBusy = false) }
            }
        }
    }

    fun updateDraft(draft: Persona) {
        _state.update { it.copy(extractedDraft = draft) }
    }

    fun saveDraft() {
        val draft = _state.value.extractedDraft ?: return
        viewModelScope.launch {
            PersonaStore.save(getApplication(), draft)
            val personas = PersonaStore.list(getApplication())
            _state.update {
                it.copy(
                    personas = personas,
                    extractedDraft = null,
                    personaProgress = "",
                    activePersonaId = draft.id,
                )
            }
            AppPrefs.setActivePersona(getApplication(), draft.id)
            applyActivePersona(force = true)
        }
    }

    fun discardDraft() {
        _state.update { it.copy(extractedDraft = null, personaProgress = "") }
    }

    fun deletePersona(id: String) {
        viewModelScope.launch {
            PersonaStore.delete(getApplication(), id)
            if (_state.value.activePersonaId == id) {
                setActivePersona(null)
            }
            refreshPersonas()
        }
    }

    fun resetConversation() {
        viewModelScope.launch {
            _state.update { it.copy(messages = emptyList()) }
            llm.reset()
            applyActivePersona(force = true)
        }
    }

    fun clearStatus() = _state.update { it.copy(status = "") }

    fun onPermissionResult(granted: Boolean) {
        _state.update { it.copy(permissionNeeded = !granted) }
    }

    override fun onCleared() {
        speaker?.shutdown()
        runCatching { recorder?.stop() }
        llm.release()
        stt.release()
        super.onCleared()
    }
}
