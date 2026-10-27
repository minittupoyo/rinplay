package com.example.myplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay

@Composable
fun NowPlayingBar(controller: MediaController, modifier: Modifier = Modifier) {
    var isPlaying by remember { mutableStateOf(controller.isPlaying) }
    var item by remember { mutableStateOf(controller.currentMediaItem) }
    var durationMs by remember { mutableLongStateOf(controller.duration) }
    var positionMs by remember { mutableLongStateOf(controller.currentPosition) }
    var dragging by remember { mutableStateOf(false) }

    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                item = mediaItem
                positionMs = 0L
            }

            override fun onPlaybackStateChanged(state: Int) {
                durationMs = controller.duration
            }
        }
        controller.addListener(listener)
        onDispose { controller.removeListener(listener) }
    }

    // 再生中だけ位置を 500ms ごとに取り直す。ドラッグ中は指の位置を優先する。
    LaunchedEffect(controller, isPlaying) {
        while (true) {
            if (!dragging) positionMs = controller.currentPosition
            durationMs = controller.duration
            if (!isPlaying) break
            delay(500)
        }
    }

    val metadata: MediaMetadata? = item?.mediaMetadata
    val knownDuration = durationMs.coerceAtLeast(0L)

    Surface(tonalElevation = 3.dp, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = metadata?.title?.toString() ?: "停止中",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
            )
            Slider(
                value = if (knownDuration > 0) positionMs.coerceIn(0L, knownDuration).toFloat() else 0f,
                onValueChange = {
                    dragging = true
                    positionMs = it.toLong()
                },
                onValueChangeFinished = {
                    controller.seekTo(positionMs)
                    dragging = false
                },
                valueRange = 0f..(if (knownDuration > 0) knownDuration.toFloat() else 1f),
                enabled = knownDuration > 0,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("${format(positionMs)} / ${format(knownDuration)}", style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { controller.seekToPrevious() }) { Text("⏮") }
                    TextButton(onClick = { if (isPlaying) controller.pause() else controller.play() }) {
                        Text(if (isPlaying) "❚❚" else "▶")
                    }
                    TextButton(onClick = { controller.seekToNext() }) { Text("⏭") }
                }
            }
        }
    }
}

private fun format(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
