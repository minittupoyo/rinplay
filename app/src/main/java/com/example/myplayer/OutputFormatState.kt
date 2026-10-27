package com.example.myplayer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** AudioTrack に実際に渡されている出力フォーマット。サービスが書き、UI が読む。 */
data class OutputFormat(
    val sampleRateHz: Int,
    val bitDepth: Int,
    val isFloat: Boolean,
    val channelCount: Int,
    val bitPerfect: String = "",
)

object OutputFormatState {
    private val _format = MutableStateFlow<OutputFormat?>(null)
    val format: StateFlow<OutputFormat?> = _format

    fun set(value: OutputFormat?) {
        _format.value = value
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
