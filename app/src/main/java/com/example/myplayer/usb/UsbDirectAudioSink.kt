package com.example.myplayer.usb

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.example.myplayer.OutputFormat
import com.example.myplayer.OutputFormatState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

/**
 * USB DAC に OS のミキサーを通さず PCM を送る AudioSink。
 * 使えない場合(DAC なし・権限なし・DAC が非対応のレートや形式・ステレオ以外)は [delegate] にそのまま任せる。
 * 音量は変えない(ビットパーフェクトのため)。音量は DAC / ヘッドホン側で調整する。
 */
@UnstableApi
class UsbDirectAudioSink(
    private val context: Context,
    private val delegate: AudioSink,
) : ForwardingAudioSink(delegate) {

    private var usb: UsbOutput? = null
    private var useUsb = false
    private var usbFailed = false

    private var format: Format? = null
    private var rate = 0
    private var encoding = C.ENCODING_INVALID
    private var written = 0L // 送り込んだフレーム数
    private var startUs = 0L
    private var needStart = true
    private var handledEos = false
    private var playing = false
    private var scratch = ByteArray(0)

    // 前のバッファの端に残った、1 フレームに満たない端数(次のバッファの先頭で補う)
    private val carry = ByteArray(8)
    private var carryLen = 0
    private var firstBuffer = true
    private var stalledSinceMs = 0L

    override fun configure(config: AudioSink.AudioSinkConfig) {
        val f = config.format
        val bits = inputBits(f)
        if (!usbFailed && bits != null) {
            val out = reuseOrOpen(f.sampleRate, bits)
            if (out != null) {
                if (!useUsb) delegate.reset()
                useUsb = true
                format = f
                rate = f.sampleRate
                encoding = f.pcmEncoding
                resetStreamState()
                out.setPaused(!playing)
                OutputFormatState.set(
                    OutputFormat(rate, out.alt.bitResolution, isFloat = false, channelCount = 2, bitPerfect = "USB直結(ビットパーフェクト)"),
                )
                return
            }
        }
        if (useUsb) closeUsb()
        useUsb = false
        super.configure(config)
    }

    /** 入力 PCM のうち、実際に意味のあるビット数。USB に送れない形式は null。 */
    private fun inputBits(f: Format): Int? {
        if (f.sampleMimeType != MimeTypes.AUDIO_RAW || f.channelCount != 2 || f.sampleRate <= 0) return null
        return when (f.pcmEncoding) {
            C.ENCODING_PCM_16BIT -> 16
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT -> 32
            C.ENCODING_PCM_FLOAT -> 24 // 24bit 以下の元ファイルを float にしたものは、24bit で欠けなく表せる
            else -> null
        }
    }

    private fun reuseOrOpen(sampleRate: Int, bits: Int): UsbOutput? {
        usb?.let {
            if (it.alive && it.rate == sampleRate && it.alt.bitResolution >= bits) return it
            closeUsb()
        }
        usb = UsbOutput.open(context, sampleRate, bits)
        return usb
    }

    private fun closeUsb() {
        usb?.close()
        usb = null
        OutputFormatState.set(null)
    }

    private fun resetStreamState() {
        written = 0
        needStart = true
        carryLen = 0
        firstBuffer = true
        handledEos = false
        usb?.flush()
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!useUsb) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        val out = usb
        if (out == null || !out.alive) {
            // DAC が外れた等。以後は OS の出力に戻す。
            Log.w(TAG, "USB output lost; falling back")
            usbFailed = true
            closeUsb()
            useUsb = false
            throw AudioSink.WriteException(-1, format!!, /* isRecoverable = */ true)
        }
        if (needStart) {
            startUs = presentationTimeUs
            needStart = false
        }
        val inBytes = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            else -> 4
        }
        val inFrame = inBytes * 2
        val outFrame = out.bytesPerFrame
        if (firstBuffer) {
            firstBuffer = false
            Log.i(TAG, "first buffer: encoding=$encoding remaining=${buffer.remaining()} inFrame=$inFrame order=${buffer.order()}")
        }

        // 前のバッファの端に 1 フレームに満たない端数が残っていたら、このバッファの先頭で補って 1 フレームにして出す。
        if (carryLen > 0) {
            while (carryLen < inFrame && buffer.hasRemaining()) carry[carryLen++] = buffer.get()
            if (carryLen < inFrame) return true // このバッファは使い切った(まだ端数)
            if (out.freeBytes() < outFrame) return accepted(buffer, out, progressed = false)
            if (scratch.size < outFrame) scratch = ByteArray(outFrame)
            convert(ByteBuffer.wrap(carry, 0, inFrame), 1, out.alt.subslotSize)
            written += out.write(scratch, 0, outFrame) / outFrame
            carryLen = 0
        }

        val frames = minOf(buffer.remaining() / inFrame, out.freeBytes() / outFrame)
        if (frames > 0) {
            if (scratch.size < frames * outFrame) scratch = ByteArray(frames * outFrame)
            convert(buffer, frames, out.alt.subslotSize)
            written += out.write(scratch, 0, frames * outFrame) / outFrame
        }
        // 端が 1 フレームに満たなければ、次のバッファに持ち越す(残したままだと永久に進めなくなる)。
        if (buffer.hasRemaining() && buffer.remaining() < inFrame) {
            Log.i(TAG, "partial frame carried: ${buffer.remaining()} of $inFrame bytes")
            while (buffer.hasRemaining()) carry[carryLen++] = buffer.get()
        }
        return accepted(buffer, out, progressed = frames > 0)
    }

    /** バッファを使い切ったかを返す。進めずに 1 秒以上詰まったら、原因の手がかりをログに出す。 */
    private fun accepted(buffer: ByteBuffer, out: UsbOutput, progressed: Boolean): Boolean {
        val done = !buffer.hasRemaining()
        val now = android.os.SystemClock.elapsedRealtime()
        if (done || progressed) {
            stalledSinceMs = 0
        } else if (stalledSinceMs == 0L) {
            stalledSinceMs = now
        } else if (now - stalledSinceMs > 1000) {
            stalledSinceMs = now
            Log.w(
                TAG,
                "stalled: remaining=${buffer.remaining()} free=${out.freeBytes()} played=${out.framesPlayed()} " +
                    "written=$written alive=${out.alive} playing=$playing carry=$carryLen",
            )
        }
        return done
    }

    /** 入力 [frames] フレームを、上位詰め 32bit 整数を経由して DAC の 1 サンプル幅(subslot)に詰める。 */
    private fun convert(src: ByteBuffer, frames: Int, subslot: Int) {
        val b = src.order(ByteOrder.LITTLE_ENDIAN)
        var o = 0
        val samples = frames * 2
        for (i in 0 until samples) {
            val v: Int = when (encoding) {
                C.ENCODING_PCM_16BIT -> b.short.toInt() shl 16
                C.ENCODING_PCM_24BIT -> {
                    val lo = b.get().toInt() and 0xFF
                    val mid = b.get().toInt() and 0xFF
                    val hi = b.get().toInt()
                    (hi shl 24) or (mid shl 16) or (lo shl 8)
                }
                C.ENCODING_PCM_32BIT -> b.int
                else -> (b.float * 2147483648.0).roundToLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            }
            // 上位 subslot バイトをリトルエンディアンで出す。
            for (k in 4 - subslot until 4) scratch[o++] = (v shr (8 * k)).toByte()
        }
    }

    override fun play() {
        if (useUsb) {
            playing = true
            usb?.setPaused(false)
        } else {
            super.play()
        }
    }

    override fun pause() {
        if (useUsb) {
            playing = false
            usb?.setPaused(true)
        } else {
            super.pause()
        }
    }

    override fun handleDiscontinuity() {
        if (!useUsb) super.handleDiscontinuity()
    }

    override fun playToEndOfStream() {
        if (useUsb) handledEos = true else super.playToEndOfStream()
    }

    override fun isEnded(): Boolean {
        if (!useUsb) return super.isEnded()
        val out = usb ?: return true
        return handledEos && out.framesPlayed() >= written
    }

    override fun hasPendingData(): Boolean {
        if (!useUsb) return super.hasPendingData()
        val out = usb ?: return false
        return written > out.framesPlayed()
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (!useUsb) return super.getCurrentPositionUs(sourceEnded)
        val out = usb ?: return AudioSink.CURRENT_POSITION_NOT_SET
        if (needStart) return AudioSink.CURRENT_POSITION_NOT_SET
        return startUs + out.framesPlayed() * 1_000_000L / rate
    }

    override fun setVolume(volume: Float) {
        if (!useUsb) super.setVolume(volume)
    }

    override fun flush() {
        if (useUsb) resetStreamState() else super.flush()
    }

    override fun reset() {
        if (useUsb) {
            closeUsb()
            useUsb = false
        }
        super.reset()
    }

    override fun release() {
        closeUsb()
        useUsb = false
        super.release()
    }

    private companion object {
        const val TAG = "UsbDirectSink"
    }
}
