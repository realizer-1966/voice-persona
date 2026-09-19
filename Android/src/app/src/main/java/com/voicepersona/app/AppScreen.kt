package com.voicepersona.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private enum class Tab(val label: String) { CHAT("대화"), PERSONA("페르소나"), SETUP("설정") }

@Composable
fun AppScaffold(state: UiState, vm: AppViewModel, onRequestMic: () -> Unit) {
    var tab by remember { mutableStateOf(Tab.CHAT) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.CHAT,
                    onClick = { tab = Tab.CHAT },
                    icon = { Icon(Icons.Filled.Mic, contentDescription = null) },
                    label = { Text(Tab.CHAT.label) },
                )
                NavigationBarItem(
                    selected = tab == Tab.PERSONA,
                    onClick = { tab = Tab.PERSONA },
                    icon = { Icon(Icons.Filled.Person, contentDescription = null) },
                    label = { Text(Tab.PERSONA.label) },
                )
                NavigationBarItem(
                    selected = tab == Tab.SETUP,
                    onClick = { tab = Tab.SETUP },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(Tab.SETUP.label) },
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.CHAT -> ChatScreen(state, vm, onRequestMic)
                Tab.PERSONA -> PersonaScreen(state, vm)
                Tab.SETUP -> SetupScreen(state, vm)
            }
        }
    }
}

// ------------------------------------------------------------------ 대화

@Composable
private fun ChatScreen(state: UiState, vm: AppViewModel, onRequestMic: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        PersonaBanner(state, vm)

        if (!state.modelsOk) {
            SetupPrompt(state, vm)
            return@Column
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.messages.isEmpty()) {
                item { EmptyChatHint(state) }
            }
            items(state.messages, key = { it.id }) { message ->
                ChatBubble(message)
            }
        }

        if (state.status.isNotBlank()) {
            Text(
                text = state.status,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        ComposerRow(
            input = input,
            onInputChange = { input = it },
            busy = state.busy,
            recording = state.recording,
            ttsEnabled = state.ttsEnabled,
            onSend = {
                val text = input
                input = ""
                vm.sendText(text)
            },
            onMic = { vm.toggleRecording() },
            onToggleTts = { vm.setTtsEnabled(!state.ttsEnabled) },
            onRequestMic = onRequestMic,
        )
    }
}

@Composable
private fun PersonaBanner(state: UiState, vm: AppViewModel) {
    val persona = state.activePersona
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = persona?.name ?: "기본 대화 상대",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = persona?.summaryLine() ?: "페르소나를 만들면 그 말투로 답합니다",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.messages.isNotEmpty()) {
            IconButton(onClick = { vm.resetConversation() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "대화 초기화")
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun EmptyChatHint(state: UiState) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp, start = 16.dp, end = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("음성으로 대화하기", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "마이크 버튼을 누르고 말한 뒤 다시 누르면, " +
                "인식한 문장을 삼진 Bonsai 1.7B에 보내고 답을 읽어줍니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = state.asrInfo.ifBlank { "음성 모델 준비 중" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val bubbleColor = if (message.fromUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (message.fromUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = bubbleColor),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(0.86f),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = message.text.ifBlank { "..." },
                    color = textColor,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (message.pending) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = textColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerRow(
    input: String,
    onInputChange: (String) -> Unit,
    busy: Boolean,
    recording: Boolean,
    ttsEnabled: Boolean,
    onSend: () -> Unit,
    onMic: () -> Unit,
    onToggleTts: () -> Unit,
    onRequestMic: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggleTts) {
            Icon(
                if (ttsEnabled) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                contentDescription = "읽어주기",
            )
        }
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("직접 입력하거나 마이크를 누르세요") },
            maxLines = 3,
        )
        Spacer(Modifier.width(6.dp))
        FilledIconButton(onClick = onSend, enabled = input.isNotBlank() && !busy) {
            Icon(Icons.Filled.Send, contentDescription = "보내기")
        }
        Spacer(Modifier.width(6.dp))
        FilledIconButton(
            onClick = {
                if (!recording) onRequestMic()
                onMic()
            },
            enabled = !busy,
        ) {
            Icon(
                if (recording) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = "녹음",
                tint = if (recording) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

// --------------------------------------------------------------- 페르소나

@Composable
private fun PersonaScreen(state: UiState, vm: AppViewModel) {
    var pendingLabel by remember { mutableStateOf<String?>(null) }
    val pickAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val label = pendingLabel ?: uri.lastPathSegment.orEmpty()
            vm.buildPersonaFromAudio(uri, label)
        }
        pendingLabel = null
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
    ) {
        Text("페르소나", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "기존 대화 녹음 파일을 고르면, 음성을 글로 옮기고 " +
                "그 내용으로 말투·성격·자주 쓰는 표현을 뽑아 페르소나를 만듭니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                pendingLabel = "대화 녹음"
                pickAudio.launch(arrayOf("audio/*", "video/*"))
            },
            enabled = !state.personaBusy && state.modelsOk,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("녹음 파일로 페르소나 만들기")
        }

        if (state.personaBusy || state.personaProgress.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.personaBusy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(state.personaProgress, style = MaterialTheme.typography.bodyMedium)
            }
        }

        state.extractedDraft?.let { draft ->
            Spacer(Modifier.height(14.dp))
            DraftEditor(draft, vm)
        }

        Spacer(Modifier.height(18.dp))
        HorizontalDivider()
        Spacer(Modifier.height(10.dp))
        Text("저장된 페르소나", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))

        if (state.personas.isEmpty()) {
            Text(
                "아직 없습니다",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.personas.forEach { persona ->
            val active = persona.id == state.activePersonaId
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            persona.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (active) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            persona.summaryLine(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (persona.catchphrases.isNotEmpty()) {
                            Text(
                                persona.catchphrases.take(4).joinToString(" "),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (active) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                    if (!active) {
                        TextButton(onClick = { vm.setActivePersona(persona.id) }) { Text("선택") }
                    } else {
                        Icon(Icons.Filled.Check, contentDescription = "사용 중")
                    }
                    IconButton(onClick = { vm.deletePersona(persona.id) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "삭제")
                    }
                }
            }
        }
        if (state.personas.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = { vm.setActivePersona(null) },
                enabled = state.activePersonaId != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("기본 대화 상대로 되돌리기") }
        }
    }
}

@Composable
private fun DraftEditor(draft: Persona, vm: AppViewModel) {
    var name by remember(draft.id) { mutableStateOf(draft.name) }
    var tone by remember(draft.id) { mutableStateOf(draft.tone) }
    var style by remember(draft.id) { mutableStateOf(draft.speechStyle) }
    var traits by remember(draft.id) { mutableStateOf(draft.traits.joinToString(", ")) }
    var phrases by remember(draft.id) { mutableStateOf(draft.catchphrases.joinToString(", ")) }
    var background by remember(draft.id) { mutableStateOf(draft.background) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("추출된 페르소나", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(name, { name = it }, label = { Text("이름") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(tone, { tone = it }, label = { Text("말투") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(style, { style = it }, label = { Text("화법") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(traits, { traits = it }, label = { Text("성격 (쉼표로 구분)") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(phrases, { phrases = it }, label = { Text("자주 쓰는 표현 (쉼표로 구분)") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(background, { background = it }, label = { Text("배경") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))

            if (draft.transcript.isNotBlank()) {
                Text("녹취록 미리보기", style = MaterialTheme.typography.labelSmall)
                Text(
                    draft.transcript.take(500),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        vm.updateDraft(
                            draft.copy(
                                name = name.trim(),
                                tone = tone.trim(),
                                speechStyle = style.trim(),
                                traits = traits.split(',').map { it.trim() }.filter { it.isNotBlank() },
                                catchphrases = phrases.split(',').map { it.trim() }.filter { it.isNotBlank() },
                                background = background.trim(),
                            )
                        )
                        vm.saveDraft()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("저장하고 사용") }
                OutlinedButton(onClick = { vm.discardDraft() }) { Text("버리기") }
            }
        }
    }
}

// ------------------------------------------------------------------ 설정

@Composable
private fun SetupScreen(state: UiState, vm: AppViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
    ) {
        val context = LocalContext.current
        Text("모델", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "준비된 모델 ${state.modelsReady}/${state.modelsTotal}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vm.refreshModels() }) { Text("새로고침") }
        }

        state.requiredModels.forEach { spec ->
            val ready = ModelStore.state(context, spec) == ModelState.READY
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (ready) "✅" else "⬜",
                    modifier = Modifier.width(28.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(spec.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${spec.approxBytes / 1_000_000} MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Text("언어 모델 선택", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        ModelCatalog.llmOptions.forEach { option ->
            val selected = option.id == state.selectedLlmId
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(option.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${option.approxBytes / 1_000_000} MB" +
                            if (option.id == ModelCatalog.llm4B.id) " · 권장" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (selected) {
                    Icon(Icons.Filled.Check, contentDescription = "선택됨")
                } else {
                    TextButton(
                        onClick = { vm.setLlmModel(option.id) },
                        enabled = !state.busy && state.downloading == null,
                    ) { Text("선택") }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        val downloading = state.downloading
        if (downloading != null) {
            Text(
                "${downloading.label} 내려받는 중 ${(state.downloadProgress * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(6.dp))
            ProgressBar(state.downloadProgress)
        } else {
            Button(
                onClick = { vm.downloadModels() },
                enabled = !state.modelsOk && !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.modelsOk) "모델 준비 완료" else "모델 내려받기 (약 645 MB)") }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "처음 한 번만 내려받습니다. 이후에는 비행기 모드에서도 동작합니다.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(18.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text("음성 인식 모델", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        ModelCatalog.speech.forEach { option ->
            val selected = option.id == state.selectedAsrId
            val ready = ModelStore.state(context, option) == ModelState.READY
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = (if (ready) "✅ " else "⬜ ") + option.label,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "${option.approxBytes / 1_000_000} MB · " +
                            if (option.languages.size > 2) {
                                "언어 자동 감지"
                            } else {
                                option.languages.joinToString("/")
                            },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (option.note.isNotBlank()) {
                        Text(
                            option.note,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                if (selected) {
                    Icon(Icons.Filled.Check, contentDescription = "선택됨")
                } else {
                    TextButton(
                        onClick = { vm.setAsrModel(option.id) },
                        enabled = !state.busy && state.downloading == null,
                    ) { Text("선택") }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("답변 읽어주기", style = MaterialTheme.typography.titleMedium)
                Text(
                    "기기 TTS로 답변을 소리내어 읽습니다",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.ttsEnabled, onCheckedChange = { vm.setTtsEnabled(it) })
        }

        Spacer(Modifier.height(18.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text("엔진 정보", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "언어: " + state.llmInfo.ifBlank { "미로드" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "음성: " + state.asrInfo.ifBlank { "미로드" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "모든 추론은 기기 안에서만 실행됩니다. 녹음과 녹취록은 외부로 전송되지 않습니다.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun SetupPrompt(state: UiState, vm: AppViewModel) {
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("모델이 필요합니다", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))
        Text(
            "삼진 Bonsai 1.7B 와 음성 인식 모델을 내려받으면 대화를 시작할 수 있습니다.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { vm.downloadModels() },
            enabled = state.downloading == null,
        ) { Text("모델 내려받기") }
        if (state.downloading != null) {
            Spacer(Modifier.height(12.dp))
            ProgressBar(state.downloadProgress)
            Spacer(Modifier.height(6.dp))
            Text(
                "${(state.downloadProgress * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun ProgressBar(progress: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(6.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
        )
    }
}
