package net.minittu.rinplay

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import java.util.Locale

/**
 * 再生中のファイル自体の形式(コーデック・ビット深度・サンプリングレート・ビットレート)。
 * AudioTrack に渡す形式([OutputFormat])とは別。例: FLAC 24bit なら出力は 32-bit float などになりうる。
 */
data class SourceFormat(
    /** 「FLAC」「MP3」「AAC」など */
    val codec: String,
    val lossless: Boolean,
    val sampleRateHz: Int,
    /** ロスレスのビット深度。取れなければ null */
    val bitDepth: Int?,
    val isFloat: Boolean,
    /** ロッシーの kbps。取れなければ null */
    val bitrateKbps: Int?,
    val channelCount: Int,
) {
    /**
     * 画面に出す短い表記。
     * ロスレス: `FLAC 24bit 48kHz` / ロッシー: `MP3 320kbps 44.1kHz`
     */
    fun label(): String = buildList {
        add(codec)
        if (lossless) {
            bitDepth?.let { add(if (isFloat) "${it}bit float" else "${it}bit") }
        } else {
            bitrateKbps?.let { add("${it}kbps") }
        }
        if (sampleRateHz > 0) add(sampleRateText(sampleRateHz))
    }.joinToString(" ")
}

/** 「44.1kHz」「48kHz」 */
fun sampleRateText(hz: Int): String {
    val khz = if (hz % 1000 == 0) (hz / 1000).toString() else String.format(Locale.US, "%.1f", hz / 1000.0)
    return "${khz}kHz"
}

/** ExoPlayer が教えてくれる入力フォーマットから、画面用の [SourceFormat] を作る。 */
fun Format.toSourceFormat(): SourceFormat? {
    val mime = sampleMimeType ?: containerMimeType ?: return null
    if (!MimeTypes.isAudio(mime)) return null

    val (codec, lossless) = when (mime) {
        MimeTypes.AUDIO_FLAC -> "FLAC" to true
        MimeTypes.AUDIO_ALAC -> "ALAC" to true
        // PCM。WAV に入っているものは WAV と表示する。
        MimeTypes.AUDIO_RAW -> (if (containerMimeType == MimeTypes.AUDIO_WAV) "WAV" else "PCM") to true
        MimeTypes.AUDIO_MPEG -> "MP3" to false
        MimeTypes.AUDIO_MPEG_L2 -> "MP2" to false
        MimeTypes.AUDIO_AAC -> "AAC" to false
        MimeTypes.AUDIO_OPUS -> "Opus" to false
        MimeTypes.AUDIO_VORBIS -> "Vorbis" to false
        MimeTypes.AUDIO_AC3 -> "AC-3" to false
        MimeTypes.AUDIO_E_AC3 -> "E-AC-3" to false
        else -> mime.substringAfter('/').removePrefix("x-").uppercase(Locale.US) to false
    }

    val depth = when (pcmEncoding) {
        C.ENCODING_PCM_8BIT -> 8
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 32
        else -> null
    }

    // 公称(Format.bitrate)があればそれを、なければ平均を使う。kbps に丸める。
    val bps = if (bitrate > 0) bitrate else averageBitrate
    return SourceFormat(
        codec = codec,
        lossless = lossless,
        sampleRateHz = sampleRate.takeIf { it > 0 } ?: 0,
        bitDepth = depth.takeIf { lossless },
        isFloat = pcmEncoding == C.ENCODING_PCM_FLOAT,
        bitrateKbps = if (!lossless && bps > 0) (bps + 500) / 1000 else null,
        channelCount = channelCount.takeIf { it > 0 } ?: 0,
    )
}

/** イベントが指す曲の mediaId。先読み中の次の曲でも、その曲の ID を返す。 */
fun Timeline.mediaIdOf(windowIndex: Int, periodUid: Any?): String? {
    if (isEmpty) return null
    val index = if (periodUid != null) {
        val period = Timeline.Period()
        if (getIndexOfPeriod(periodUid) == C.INDEX_UNSET) windowIndex else getPeriodByUid(periodUid, period).windowIndex
    } else {
        windowIndex
    }
    if (index !in 0 until windowCount) return null
    return getWindow(index, Timeline.Window()).mediaItem.mediaId
}
