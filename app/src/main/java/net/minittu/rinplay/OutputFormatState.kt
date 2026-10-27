package net.minittu.rinplay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** 音の出口。画面の再生情報アイコンの状態になる。 */
enum class OutputPath {
    /** USB DAC に OS のミキサーを通さず直接出力(UsbDirectAudioSink) */
    USB_DIRECT,

    /** OS のビットパーフェクト出力(setPreferredMixerAttributes が通ったとき) */
    BIT_PERFECT,

    /** OS のミキサー経由(リサンプルや音量処理が入りうる) */
    MIXER,
}

/** AudioTrack(または USB 直結)に実際に渡されている出力フォーマット。サービスが書き、UI が読む。 */
data class OutputFormat(
    val sampleRateHz: Int,
    val bitDepth: Int,
    val isFloat: Boolean,
    val channelCount: Int,
    val bitPerfect: String = "",
    val path: OutputPath = OutputPath.MIXER,
)

object OutputFormatState {
    private val _format = MutableStateFlow<OutputFormat?>(null)
    val format: StateFlow<OutputFormat?> = _format

    fun set(value: OutputFormat?) {
        _format.value = value
    }
}

/**
 * 曲ごとの元ファイルの形式。キーは mediaId。
 * ExoPlayer は次の曲を先読みして形式を通知するので、再生中の曲の分を上書きしないよう、曲ごとに持つ。
 */
object SourceFormatState {
    private const val MAX_ENTRIES = 8

    private val _formats = MutableStateFlow<Map<String, SourceFormat>>(emptyMap())
    val formats: StateFlow<Map<String, SourceFormat>> = _formats

    fun put(mediaId: String, format: SourceFormat) {
        // 古いものから捨てる(LinkedHashMap の挿入順)。同じ曲を入れ直したら最後に回す。
        val next = LinkedHashMap(_formats.value)
        next.remove(mediaId)
        next[mediaId] = format
        while (next.size > MAX_ENTRIES) next.remove(next.keys.first())
        _formats.value = next
    }

    fun clear() {
        _formats.value = emptyMap()
    }
}

/** 「44.1 kHz」「96 kHz」のようなサンプリングレートの表示。 */
val OutputFormat.sampleRateLabel: String
    get() {
        val text = if (sampleRateHz % 1000 == 0) {
            (sampleRateHz / 1000).toString()
        } else {
            String.format(Locale.US, "%.1f", sampleRateHz / 1000.0)
        }
        return "$text kHz"
    }

/** 「24-bit」「32-bit float」のようなビット深度の表示。 */
val OutputFormat.bitDepthLabel: String
    get() = if (isFloat) "$bitDepth-bit float" else "$bitDepth-bit"

val OutputFormat.channelLabel: String
    get() = when (channelCount) {
        1 -> "モノラル"
        2 -> "ステレオ"
        else -> "${channelCount}ch"
    }

/** ミニプレイヤー用。「44.1 kHz · 32-bit float」 */
fun OutputFormat.shortLabel(): String = "$sampleRateLabel · $bitDepthLabel"

/** 全画面の再生中用。「44.1 kHz · 32-bit float · ステレオ」 */
fun OutputFormat.fullLabel(): String = "$sampleRateLabel · $bitDepthLabel · $channelLabel"
