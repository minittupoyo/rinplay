package com.example.myplayer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 画面下の小さなプレイヤー。タップで全画面の「再生中」を開く。
 * 補足の行には、アーティストと、いまの出力フォーマット(高音質の確認用)を常に出す。
 */
@Composable
fun MiniPlayer(
    state: PlaybackUiState,
    format: OutputFormat?,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val metadata = state.metadata
    val controller = state.controller
    Surface(
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth().clickable(onClick = onExpand),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            ) {
                Artwork(metadata.artworkData ?: metadata.artworkUri, Icons.Rounded.MusicNote, Modifier.size(48.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = metadata.title?.toString().orEmpty().ifEmpty { "再生中" },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle = listOfNotNull(
                        metadata.artist?.toString()?.takeIf { it.isNotEmpty() },
                        format?.shortLabel(),
                    ).joinToString(" · ")
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = { if (state.isPlaying) controller.pause() else controller.play() }) {
                    Icon(
                        if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (state.isPlaying) "一時停止" else "再生",
                        modifier = Modifier.size(28.dp),
                    )
                }
                IconButton(onClick = { controller.seekToNext() }) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "次の曲", modifier = Modifier.size(28.dp))
                }
            }
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
        }
    }
}
