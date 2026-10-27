package com.example.myplayer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
