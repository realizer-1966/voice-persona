package com.voicepersona.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: AppViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()

            val micPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted -> vm.onPermissionResult(granted) }

            LaunchedEffect(state.permissionNeeded) {
                if (state.permissionNeeded) micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }

            VoicePersonaTheme {
                AppScaffold(
                    state = state,
                    vm = vm,
                    onRequestMic = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                )
            }
        }
    }
}
