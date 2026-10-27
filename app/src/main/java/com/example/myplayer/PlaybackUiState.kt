package com.example.myplayer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay

/**
 * 再生中の状態(再生/停止、曲、位置、シャッフル、リピート)。
 * ミニプレイヤーと全画面の「再生中」で共有するので、1 か所で [Player.Listener] を受ける。
 */
@Stable
class PlaybackUiState(val controller: MediaController) {
    var isPlaying by mutableStateOf(controller.isPlaying)
    var mediaItem by mutableStateOf(controller.currentMediaItem)

    /** 曲のタイトルなど。ストリームから取れた埋め込みジャケット(artworkData)も含む。 */
    var metadata by mutableStateOf(controller.mediaMetadata)
    var durationMs by mutableLongStateOf(controller.duration)
    var positionMs by mutableLongStateOf(controller.currentPosition)
    var shuffle by mutableStateOf(controller.shuffleModeEnabled)
    var repeatMode by mutableIntStateOf(controller.repeatMode)

    val hasMedia: Boolean get() = mediaItem != null

    /** 進捗(0..1)。長さが分からないときは 0。 */
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    fun refreshPosition() {
        positionMs = controller.currentPosition
        durationMs = controller.duration
    }
}

/** [controller] がつながっていれば、その再生状態を返す。つながるまでは null。 */
@Composable
fun rememberPlaybackUiState(controller: MediaController?): PlaybackUiState? {
    if (controller == null) return null
    val state = remember(controller) { PlaybackUiState(controller) }

    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                state.isPlaying = isPlaying
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                state.mediaItem = mediaItem
                state.positionMs = 0L
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                state.metadata = mediaMetadata
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                state.durationMs = controller.duration
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                state.shuffle = shuffleModeEnabled
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                state.repeatMode = repeatMode
            }
        }
        controller.addListener(listener)
        onDispose { controller.removeListener(listener) }
    }

    // 再生中だけ位置を 500ms ごとに取り直す(停止中は 1 回だけ)。
    LaunchedEffect(controller, state.isPlaying) {
        while (true) {
            state.refreshPosition()
            if (!state.isPlaying) break
            delay(500)
        }
    }
    return state
}
