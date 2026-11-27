package com.mundiah.mimunclip.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.mundiah.mimunclip.ui.screens.HomeScreen
import com.mundiah.mimunclip.ui.screens.HookPickerScreen
import com.mundiah.mimunclip.ui.screens.ResultsScreen
import com.mundiah.mimunclip.ui.screens.SettingsScreen

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* ditolak pun proses tetap jalan; notifikasi hanya tidak tampil */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        setContent {
            val state by viewModel.state.collectAsState()
            val darkTheme = state.settings.darkTheme
            val colors = when (darkTheme) {
                true -> darkColorScheme()
                false -> lightColorScheme()
                null -> if (androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppScaffold(viewModel = viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun AppScaffold(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MimunClip") },
                actions = {
                    if (state.screen != Screen.SETTINGS) {
                        TextButton(onClick = { viewModel.goTo(Screen.SETTINGS) }) {
                            Text("Pengaturan")
                        }
                    }
                }
            )
        }
    ) { padding ->
        when (state.screen) {
            Screen.HOME -> HomeScreen(
                state = state,
                onPickVideo = { viewModel.pickVideo(it.first, it.second) },
                onTranscribe = { viewModel.startTranscription() },
                onSettingsChange = { viewModel.updateSettings(it) },
                onShowResults = { viewModel.goTo(Screen.RESULTS) },
                onCancelWork = { viewModel.cancelWork() },
                onClearError = { viewModel.clearError() },
                modifier = Modifier.padding(padding)
            )
            Screen.SETTINGS -> SettingsScreen(
                state = state,
                onSaveSettings = { viewModel.updateSettings(it) },
                onSaveKeys = { viewModel.saveGroqKeys(it) },
                onBack = { viewModel.goTo(Screen.HOME) },
                modifier = Modifier.padding(padding)
            )
            Screen.HOOKS -> HookPickerScreen(
                state = state,
                videoUri = state.videoUri,
                onToggle = { viewModel.toggleCandidate(it) },
                onRender = { viewModel.renderSelected() },
                onCancel = { viewModel.cancelWork(); viewModel.goTo(Screen.HOME) },
                modifier = Modifier.padding(padding)
            )
            Screen.RESULTS -> ResultsScreen(
                state = state,
                onBack = { viewModel.goTo(Screen.HOME) },
                onRemoveHistory = { viewModel.removeHistory(it) },
                modifier = Modifier.padding(padding)
            )
        }
    }
}
