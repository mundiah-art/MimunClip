package com.mundiah.mimunclip.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mundiah.mimunclip.model.QwenDefaults
import com.mundiah.mimunclip.model.RenderSettings
import com.mundiah.mimunclip.ui.UiState

/**
 * Pengaturan: Groq API key (bisa banyak, dipisah koma/baris baru),
 * model Qwen + fallback, toggle render, dan tema.
 * Key disimpan terenkripsi dan tidak pernah ditampilkan utuh.
 */
@Composable
fun SettingsScreen(
    state: UiState,
    onSaveSettings: (RenderSettings) -> Unit,
    onSaveKeys: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var keysText by remember(state.groqKeysRaw) { mutableStateOf(state.groqKeysRaw) }
    var qwenModel by remember(state.settings.qwenModel) { mutableStateOf(state.settings.qwenModel) }
    var fallbackModel by remember(state.settings.qwenFallbackModel) { mutableStateOf(state.settings.qwenFallbackModel) }
    val settings = state.settings

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Pengaturan", style = MaterialTheme.typography.titleLarge)
        }
        item {
            Text("Groq API Key", style = MaterialTheme.typography.titleSmall)
            Text(
                "Dapatkan di console.groq.com. Bisa isi beberapa key, pisahkan dengan koma " +
                    "atau baris baru — bila satu key kena limit (429), app otomatis pindah ke key berikutnya.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = keysText,
                onValueChange = { keysText = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("gsk_...") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                minLines = 2,
                maxLines = 6,
            )
            if (state.groqKeysMasked.isNotBlank()) {
                Text(
                    "Tersimpan:\n${state.groqKeysMasked}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(4.dp))
            Button(onClick = { onSaveKeys(keysText) }, modifier = Modifier.fillMaxWidth()) {
                Text("Simpan Groq Key")
            }
        }
        item {
            Text("Model AI", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = qwenModel,
                onValueChange = { qwenModel = it },
                label = { Text("Model Qwen (utama)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = fallbackModel,
                onValueChange = { fallbackModel = it },
                label = { Text("Model fallback") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Text(
                "Default: ${QwenDefaults.MODEL} (fallback: ${QwenDefaults.FALLBACK_MODEL}). " +
                    "Whisper selalu ${QwenDefaults.WHISPER_MODEL}.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    onSaveSettings(
                        settings.copy(
                            qwenModel = qwenModel.ifBlank { QwenDefaults.MODEL },
                            qwenFallbackModel = fallbackModel.ifBlank { QwenDefaults.FALLBACK_MODEL },
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Simpan Model") }
        }
        item {
            Text("Render", style = MaterialTheme.typography.titleSmall)
            ToggleRow2("Mode Kilat (Stream Copy tanpa re-encode)", settings.fastStreamCopy) {
                onSaveSettings(settings.copy(fastStreamCopy = it))
            }
            ToggleRow2("SLSR inject", settings.slsrEnabled) {
                onSaveSettings(settings.copy(slsrEnabled = it))
            }
            if (!settings.fastStreamCopy) {
                ToggleRow2("CAS 90%", settings.casEnabled) {
                    onSaveSettings(settings.copy(casEnabled = it))
                }
                ToggleRow2("Unsharp 90%", settings.unsharpEnabled) {
                    onSaveSettings(settings.copy(unsharpEnabled = it))
                }
            }
        }
        item {
            Text("Tema", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.darkTheme == null,
                    onClick = { onSaveSettings(settings.copy(darkTheme = null)) },
                    label = { Text("Sistem") }
                )
                FilterChip(
                    selected = settings.darkTheme == false,
                    onClick = { onSaveSettings(settings.copy(darkTheme = false)) },
                    label = { Text("Terang") }
                )
                FilterChip(
                    selected = settings.darkTheme == true,
                    onClick = { onSaveSettings(settings.copy(darkTheme = true)) },
                    label = { Text("Gelap") }
                )
            }
        }
        item {
            Text(
                "MimunClip v1.0.0 — video sumber & hasil tidak pernah dihapus otomatis. " +
                    "Hasil tersimpan di Downloads/MimunClip.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Kembali")
            }
        }
    }
}

@Composable
private fun ToggleRow2(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
