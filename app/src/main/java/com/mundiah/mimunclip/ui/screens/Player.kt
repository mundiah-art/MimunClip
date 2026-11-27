package com.mundiah.mimunclip.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

/**
 * Mini video player (Media3 ExoPlayer) untuk memutar hasil & preview hook.
 * [startMs]: posisi awal. [previewMs]: bila diisi, video dijeda otomatis
 * setelah durasi tersebut (dipakai untuk preview 5 detik pertama hook).
 */
@Composable
fun MiniPlayer(
    uri: Uri,
    modifier: Modifier = Modifier,
    startMs: Long = 0L,
    previewMs: Long? = null,
    autoPlay: Boolean = true,
) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            seekTo(startMs)
            playWhenReady = autoPlay
            prepare()
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    // Bila posisi awal berubah (mis. user memilih preview hook lain),
    // lompat ke posisi baru dan putar lagi.
    LaunchedEffect(startMs) {
        player.seekTo(startMs)
        player.playWhenReady = autoPlay
    }
    if (previewMs != null) {
        LaunchedEffect(uri, startMs) {
            delay(previewMs)
            player.pause()
        }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                controllerAutoShow = true
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(9f / 16f),
        update = { view -> view.player = player }
    )
}

/** Listener sekali-pakai: panggil [onEnded] saat playback mencapai akhir. */
@Suppress("unused")
fun Player.addEndedListener(onEnded: () -> Unit) {
    addListener(object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) onEnded()
        }
    })
}
