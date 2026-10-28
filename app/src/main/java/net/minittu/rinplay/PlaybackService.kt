package net.minittu.rinplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.media.AudioFormat
import android.os.Build
import android.util.Log
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
import net.minittu.rinplay.usb.UsbDac
import net.minittu.rinplay.usb.UsbDirectAudioSink
import net.minittu.rinplay.usb.UsbOutput
import net.minittu.rinplay.usb.UsbVolume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@UnstableApi
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var usbSink: UsbDirectAudioSink? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.i("PlaybackService", "USB device attached")
                    if (UsbVolume.directOutput.value && UsbDac.findAudioDevice(context) != null) {
                        // OS 経由で再生中に DAC が挿されると、OS が出力先をその DAC に切り替え、DAC の既定の音量(最大)と
                        // OS の音量(直結中は効かないので高いままのことがある)で、いきなり大きな音が鳴るおそれがある。
                        // OS が切り替えるより先に止める。直結は、権限を許可したあと、再生ボタンで始まる。
                        session?.player?.pause()
                        UsbDac.prepare(
                            context,
                            onReady = { usbSink?.onUsbAttached() },
                            onDenied = { usbSink?.onUsbPermissionDenied() },
                        )
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.i("PlaybackService", "USB device detached")
                    usbSink?.onUsbDetached()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        UsbVolume.init(this)
        // 前回、直結のまま強制終了などで終わり、DAC の音量が下がったままなら、元に戻す(OS 経由の再生が小さい音のままにならないように)。
        scope.launch(Dispatchers.IO) { UsbOutput.recoverVolume(this@PlaybackService) }
        // 直接出力が ON のとき(起動時と、設定で ON にされたとき)、DAC があれば権限を確認する。
        // 許可されたら、再生中でも直結に切り替える。OFF の間は DAC に触らない。
        scope.launch {
            UsbVolume.directOutput.collect { enabled ->
                if (enabled) {
                    UsbDac.prepare(
                        this@PlaybackService,
                        onReady = { usbSink?.onUsbAttached() },
                        onDenied = { usbSink?.onUsbPermissionDenied() },
                    )
                }
            }
        }
        // USB DAC が使えるときは UsbDirectAudioSink が OS のミキサーを通さずに出す。使えなければ通常の出力。
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink? = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
                ?.let { UsbDirectAudioSink(context, it).also { sink -> usbSink = sink } }
        }.setEnableAudioFloatOutput(true)

        // DAC の挿抜を監視する。挿されたら権限を確認して、直結を試せるようにする(再生中なら切り替える)。
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
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
                OutputFormatState.set(
                    audioTrackConfig.toOutputFormat().copy(
                        bitPerfect = bitPerfectStatus,
                        path = if (bitPerfectStatus == BitPerfect.STATUS_ENABLED) OutputPath.BIT_PERFECT else OutputPath.MIXER,
                    ),
                )
            }

            override fun onAudioTrackReleased(
                eventTime: AnalyticsListener.EventTime,
                audioTrackConfig: AudioSink.AudioTrackConfig,
            ) {
                liveTracks = (liveTracks - 1).coerceAtLeast(0)
                // USB 直結には AudioTrack がない。OS 経由から直結へ切り替えたときに遅れて届く解放で、直結の表示を消さない
                // (直結の表示は UsbDirectAudioSink が自分で消す)。
                if (liveTracks == 0 && OutputFormatState.format.value?.path != OutputPath.USB_DIRECT) {
                    OutputFormatState.set(null)
                }
            }
        })
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        scope.cancel()
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
