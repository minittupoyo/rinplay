package net.minittu.rinplay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import net.minittu.rinplay.usb.UsbVolume

/**
 * 全画面の「再生中」。上から、閉じる + 再生情報、ジャケット、曲名とアーティスト、
 * シーク、操作ボタン、DAC の音量(USB 直結のときだけ)。
 */
@Composable
fun NowPlayingScreen(
    state: PlaybackUiState,
    source: SourceFormat?,
    format: OutputFormat?,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showInfo by remember { mutableStateOf(false) }
    if (showInfo) PlaybackInfoDialog(source, format, onDismiss = { showInfo = false })

    // DLNA では、サーバーの 160px のサムネイル(全画面では 6 倍に拡大される)より、ファイルに埋め込まれたジャケットを優先する。
    val mediaItem = state.mediaItem
    val embedded = rememberEmbeddedArtwork(mediaItem?.mediaId, mediaItem?.localConfiguration?.uri?.toString())
    val artwork = state.metadata.artworkData ?: embedded ?: state.metadata.artworkUri

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NowPlayingTopBar(
                format = format,
                onCollapse = onCollapse,
                onShowInfo = { showInfo = true },
            )
            ArtworkPane(
                artwork = artwork,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp),
            )
            TrackInfo(state.metadata)
            SeekBar(state)
            TransportControls(state)
            DacVolumeSlider()
        }
    }
}

/** 上部バー。閉じるボタン、タイトル、出力の状態を示す再生情報ボタン。 */
@Composable
private fun NowPlayingTopBar(
    format: OutputFormat?,
    onCollapse: () -> Unit,
    onShowInfo: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onCollapse) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "閉じる", modifier = Modifier.size(CollapseIconSize))
        }
        Text(
            "再生中",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        PlaybackInfoButton(format = format, onClick = onShowInfo)
    }
}

/**
 * ジャケット。残りの高さに収まる最大の正方形で出す
 * (画面が低い端末でも操作ボタンが隠れないように)。
 */
@Composable
private fun ArtworkPane(artwork: Any?, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val side = if (maxWidth < maxHeight) maxWidth else maxHeight
        val shape = RoundedCornerShape(ArtworkCorner)
        Artwork(
            model = artwork,
            placeholder = Icons.Rounded.MusicNote,
            modifier = Modifier.size(side).shadow(ArtworkShadow, shape),
            shape = shape,
        )
    }
}

/** 曲名と、その下に「アーティスト — アルバム」(どちらもなければ出さない)。 */
@Composable
private fun TrackInfo(metadata: MediaMetadata) {
    Text(
        text = metadata.playerTitle,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
    val subtitle = listOfNotNull(metadata.artistName, metadata.albumTitle?.toString()?.takeIf { it.isNotEmpty() })
        .joinToString(" — ")
    if (subtitle.isNotEmpty()) {
        Text(
            text = subtitle,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

/**
 * シークバーと、経過 / 全体の時間。
 * [PlaybackUiState.positionMs] は 500ms ごとにしか更新されないので、ドラッグ中はツマミの位置を優先する
 * (離すと null に戻り、以降は曲の位置に従う)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(state: PlaybackUiState) {
    val durationMs = state.durationMs.coerceAtLeast(0L)
    val maxMs = durationMs.coerceAtLeast(1L) // 長さが 0 のときも Slider の範囲を潰さない
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    val shownPosition = dragPosition?.toLong() ?: state.positionMs

    Slider(
        value = shownPosition.coerceIn(0L, maxMs).toFloat(),
        onValueChange = { dragPosition = it },
        onValueChangeFinished = {
            dragPosition?.let { state.seekTo(it.toLong()) }
            dragPosition = null
        },
        valueRange = 0f..maxMs.toFloat(),
        enabled = durationMs > 0,
        modifier = Modifier.padding(top = 16.dp),
    )
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text(formatTime(shownPosition), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.weight(1f))
        Text(formatTime(durationMs), style = MaterialTheme.typography.labelMedium)
    }
}

/** シャッフル / 前の曲 / 再生・一時停止 / 次の曲 / リピート。 */
@Composable
private fun TransportControls(state: PlaybackUiState) {
    val controller = state.controller
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = { controller.shuffleModeEnabled = !state.shuffle }) {
            Icon(
                Icons.Rounded.Shuffle,
                contentDescription = "シャッフル",
                tint = if (state.shuffle) accent else muted,
            )
        }
        SkipButton(Icons.Rounded.SkipPrevious, "前の曲") { controller.seekToPrevious() }
        FilledIconButton(onClick = { state.togglePlayPause() }, modifier = Modifier.size(PlayButtonSize)) {
            Icon(
                if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (state.isPlaying) "一時停止" else "再生",
                modifier = Modifier.size(PlayIconSize),
            )
        }
        SkipButton(Icons.Rounded.SkipNext, "次の曲") { controller.seekToNext() }
        IconButton(onClick = { controller.repeatMode = nextRepeatMode(state.repeatMode) }) {
            Icon(
                if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                contentDescription = "リピート",
                tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) muted else accent,
            )
        }
    }
}

/** 前 / 次の曲のボタン。中央の再生ボタンより一回り小さくする。 */
@Composable
private fun SkipButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(SkipButtonSize)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(SkipIconSize))
    }
}

/**
 * DAC 自身の音量。USB 直結ではシステム音量が効かないので、ここで操作する(ビットは変わらない)。
 * 直結で DAC を開いている間だけ出る。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DacVolumeSlider() {
    val volume by UsbVolume.state.collectAsState()
    val v = volume ?: return

    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.VolumeUp, contentDescription = "DAC の音量", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = v.current.toFloat(),
            onValueChange = { UsbVolume.set(it.toInt()) },
            valueRange = v.min.toFloat()..v.max.toFloat(),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text(
            "%.1f dB".format(v.current / 256.0),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(VolumeLabelWidth),
            textAlign = TextAlign.End,
        )
    }
}

/** リピートの切り替え順: なし → 全曲 → 1 曲 → なし。 */
private fun nextRepeatMode(current: Int): Int = when (current) {
    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
    else -> Player.REPEAT_MODE_OFF
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

// --- 寸法。見た目を揃えるためにここにまとめる ---

private val CollapseIconSize = 32.dp
private val ArtworkCorner = 20.dp
private val ArtworkShadow = 12.dp
private val PlayButtonSize = 72.dp
private val PlayIconSize = 40.dp
private val SkipButtonSize = 56.dp
private val SkipIconSize = 36.dp
private val VolumeLabelWidth = 64.dp
