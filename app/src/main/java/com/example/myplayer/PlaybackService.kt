package com.example.myplayer

import android.media.AudioFormat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@UnstableApi
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val renderers = DefaultRenderersFactory(this)
            .setEnableAudioFloatOutput(true)
        val player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addAnalyticsListener(object : AnalyticsListener {
            private var bitPerfectStatus = ""

            // AudioTrack 作成より前に呼ばれるので、ここで優先ミキサー属性を設定する。
            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                // 元ファイルの形式を、曲ごとに記録する(画面の「FLAC 24bit 48kHz」など)。
                val mediaId = eventTime.timeline.mediaIdOf(eventTime.windowIndex, eventTime.mediaPeriodId?.periodUid)
                val source = format.toSourceFormat()
                if (mediaId != null && source != null) SourceFormatState.put(mediaId, source)
                if (format.sampleRate > 0) {
                    bitPerfectStatus = BitPerfect.apply(this@PlaybackService, format.sampleRate)
                }
            }

            // 新トラックの作成後に旧トラックの解放が届くことがあるので、生存数で数える。
            private var liveTracks = 0

            override fun onAudioTrackInitialized(
                eventTime: AnalyticsListener.EventTime,
                audioTrackConfig: AudioSink.AudioTrackConfig,
            ) {
                liveTracks++
                OutputFormatState.set(audioTrackConfig.toOutputFormat().copy(bitPerfect = bitPerfectStatus))
            }

            override fun onAudioTrackReleased(
                eventTime: AnalyticsListener.EventTime,
                audioTrackConfig: AudioSink.AudioTrackConfig,
            ) {
                liveTracks = (liveTracks - 1).coerceAtLeast(0)
                if (liveTracks == 0) OutputFormatState.set(null)
            }
        })
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        BitPerfect.clear(this)
        OutputFormatState.set(null)
        SourceFormatState.clear()
        super.onDestroy()
    }
}

private fun AudioSink.AudioTrackConfig.toOutputFormat(): OutputFormat {
    val depth = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 8
        AudioFormat.ENCODING_PCM_16BIT -> 16
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
        AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 32
        else -> 0
    }
    return OutputFormat(
        sampleRateHz = sampleRate,
        bitDepth = depth,
        isFloat = encoding == AudioFormat.ENCODING_PCM_FLOAT,
        channelCount = Integer.bitCount(channelConfig),
    )
}
