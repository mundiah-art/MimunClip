package com.mundiah.mimunclip.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.mundiah.mimunclip.model.ClipCandidate
import com.mundiah.mimunclip.ui.UiState

/**
 * Hook picker: setelah transkrip, sebelum render. Qwen mengusulkan 4
 * kandidat; user memilih tepat N hook untuk N klip (N = jumlah klip).
 * Tiap kandidat menampilkan hook_quote, durasi, dan rentang segmen,
 * plus preview 5 detik pertama hook di mini player.
 */
@Composable
fun HookPickerScreen(
    state: UiState,
    videoUri: Uri?,
    onToggle: (Int) -> Unit,
    onRender: () -> Unit,
    onCancel: () -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var previewCandidate by remember { mutableStateOf<ClipCandidate?>(null) }
    val clipboard = LocalClipboardManager.current
    val need = state.settings.clipCount
    val chosen = state.selectedStarts.size

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Pilih Hook", style = MaterialTheme.typography.titleLarge)
            Text(
                "Pilih tepat $need hook untuk $need klip (terpilih: $chosen). " +
                    "Klip berdurasi 35–50 detik, hook ada di awal klip.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (state.isBusy) {
            item {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(state.progressLabel, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("Batalkan")
                }
            }
        }
        if (previewCandidate != null && videoUri != null) {
            item {
                val cand = previewCandidate!!
                val startMs = ((cand.ranges.firstOrNull()?.start ?: 0.0) * 1000).toLong()
                Text(
                    "Preview 5 detik: ${cand.title}",
                    style = MaterialTheme.typography.titleSmall
                )
                MiniPlayer(
                    uri = videoUri,
                    startMs = startMs,
                    previewMs = 5_000L,
                    autoPlay = true,
                )
            }
        }
        items(state.candidates) { candidate ->
            val selected = candidate.startSegmentId in state.selectedStarts
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggle(candidate.startSegmentId) }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(candidate.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "\"${candidate.hookQuote}\"",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "${candidate.durationSec.toInt()} detik • Segmen ${candidate.rangeLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            candidate.caption,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { previewCandidate = candidate }) {
                                Text("Preview 5 dtk")
                            }
                        }
                    }
                }
            }
        }
        item {
            Button(
                onClick = onRender,
                enabled = chosen == need && !state.isBusy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Render $need Klip") }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Kembali")
            }
            if (chosen != need) {
                Text(
                    "Pilih ${need - chosen} hook lagi.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    if (state.error != null) {
        AlertDialog(
            onDismissRequest = onClearError,
            title = { Text("Terjadi masalah") },
            text = { Text(state.error) },
            confirmButton = {
                TextButton(onClick = onClearError) { Text("Tutup") }
            },
            dismissButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(state.error))
                }) { Text("Salin Error") }
            }
        )
    }
}
