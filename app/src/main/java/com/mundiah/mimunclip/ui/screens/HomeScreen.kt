package com.mundiah.mimunclip.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.mundiah.mimunclip.model.ClipResult
import com.mundiah.mimunclip.model.RenderSettings
import com.mundiah.mimunclip.ui.UiState

/**
 * Layar utama: pilih video -> atur jumlah klip & toggle -> transkripsi.
 * Riwayat hasil tampil di bawah. Alur dibuat satu layar penuh yang
 * sederhana dan fungsional (bukan tiruan FakeCLiping).
 */
@Composable
fun HomeScreen(
    state: UiState,
    onPickVideo: (Pair<Uri, String>) -> Unit,
    onTranscribe: () -> Unit,
    onSettingsChange: (RenderSettings) -> Unit,
    onShowResults: () -> Unit,
    onCancelWork: () -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        var name = "video.mp4"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(idx) ?: name
            }
        }
        onPickVideo(uri to name)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Pilih video rekaman live TikTok", style = MaterialTheme.typography.titleMedium)
            Text(
                "Video dipotong jadi klip 35–50 detik (minimal 28 detik) dengan hook di awal. " +
                    "Video sumber tidak pernah dihapus.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            OutlinedButton(
                onClick = { picker.launch("video/*") },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (state.videoUri == null) "Pilih Video" else "Ganti Video") }
        }
        if (state.videoUri != null) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(state.videoName ?: "Video", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Durasi: ${state.videoDurationSec.toInt()} detik",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
        item {
            Text("Jumlah klip", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..4).forEach { n ->
                    FilterChip(
                        selected = state.settings.clipCount == n,
                        onClick = { onSettingsChange(state.settings.copy(clipCount = n)) },
                        label = { Text("$n klip") }
                    )
                }
            }
        }
        item {
            ToggleRow(
                label = "Mode Kilat (Stream Copy tanpa re-encode)",
                checked = state.settings.fastStreamCopy,
                onChange = { onSettingsChange(state.settings.copy(fastStreamCopy = it)) }
            )
            ToggleRow(
                label = "SLSR inject",
                checked = state.settings.slsrEnabled,
                onChange = { onSettingsChange(state.settings.copy(slsrEnabled = it)) }
            )
            if (!state.settings.fastStreamCopy) {
                ToggleRow(
                    label = "CAS 90%",
                    checked = state.settings.casEnabled,
                    onChange = { onSettingsChange(state.settings.copy(casEnabled = it)) }
                )
                ToggleRow(
                    label = "Unsharp 90%",
                    checked = state.settings.unsharpEnabled,
                    onChange = { onSettingsChange(state.settings.copy(unsharpEnabled = it)) }
                )
            }
            Text(
                "Groq key: " + if (state.groqKeysMasked.isBlank()) "belum diisi — buka Pengaturan" else state.groqKeysMasked,
                style = MaterialTheme.typography.bodySmall
            )
        }
        item {
            Button(
                onClick = onTranscribe,
                enabled = state.videoUri != null && !state.isBusy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Transkripsi & Cari Hook") }
            if (state.isBusy) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(state.progressLabel, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = onCancelWork,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Batalkan") }
                state.log.takeLast(5).forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Riwayat Hasil", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onShowResults) { Text("Lihat Semua") }
            }
            if (state.history.isEmpty()) {
                Text(
                    "Belum ada hasil. Hasil render tersimpan di Downloads/MimunClip.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        items(state.history.take(5)) { result ->
            HistoryRow(result = result, onPlay = onShowResults)
        }
    }

    if (state.error != null) {
        val clipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = onClearError,
            title = { Text("Terjadi masalah") },
            text = { Text(state.error) },
            confirmButton = {
                TextButton(onClick = onClearError) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(state.error))
                }) { Text("Salin Error") }
            }
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun HistoryRow(result: ClipResult, onPlay: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(result.title, style = MaterialTheme.typography.titleSmall)
            Text(
                result.fileName + if (result.slsrApplied) " • SLSR" else " • tanpa SLSR",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onPlay) { Text("Putar") }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(result.caption))
                }) { Text("Salin Caption") }
                TextButton(onClick = {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "video/*"
                        putExtra(Intent.EXTRA_STREAM, result.contentUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(share, "Bagikan video"))
                }) { Text("Bagikan") }
            }
        }
    }
}
