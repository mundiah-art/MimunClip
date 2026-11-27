package com.mundiah.mimunclip.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.mundiah.mimunclip.model.ClipResult
import com.mundiah.mimunclip.ui.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hasil render: mini player per klip, caption + tombol salin ke clipboard,
 * info SLSR, dan riwayat lengkap. Menghapus entri riwayat TIDAK menghapus
 * file video.
 */
@Composable
fun ResultsScreen(
    state: UiState,
    onBack: () -> Unit,
    onRemoveHistory: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var playingId by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<ClipResult?>(null) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Hasil", style = MaterialTheme.typography.titleLarge)
            Text(
                "Tersimpan di Downloads/MimunClip. Ketuk Putar untuk memutar di dalam app.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Buat Klip Lagi")
            }
        }
        val all = (state.results + state.history).distinctBy { it.id }
        if (all.isEmpty()) {
            item { Text("Belum ada hasil.", style = MaterialTheme.typography.bodySmall) }
        }
        items(all) { result ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(result.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        result.fileName + " • " +
                            SimpleDateFormat("dd MMM yyyy HH:mm", Locale("id", "ID"))
                                .format(Date(result.createdAtMillis)),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        result.slsrNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (playingId == result.id) {
                        MiniPlayer(uri = result.contentUri, autoPlay = true)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            playingId = if (playingId == result.id) null else result.id
                        }) { Text(if (playingId == result.id) "Tutup" else "Putar") }
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(result.caption))
                        }) { Text("Salin Caption") }
                        TextButton(onClick = { deleteTarget = result }) {
                            Text("Hapus Riwayat")
                        }
                    }
                    Text(result.caption, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Hapus riwayat?") },
            text = {
                Text("Hanya catatan \"${deleteTarget!!.title}\" yang dihapus. File video di Downloads tetap ada.")
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveHistory(deleteTarget!!.id)
                    deleteTarget = null
                }) { Text("Hapus") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Batal") }
            }
        )
    }
}
