package com.example.myplayer.usb

import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Process
import android.util.Log

/**
 * USB DAC への 1 本の出力ストリーム(固定のサンプルレートと alt 設定)。
 * 開くとカーネルの snd-usb-audio を切り離す(再生中は OS の音がこの DAC に出ない)。close で解放する。
 */
class UsbOutput private constructor(
    private val conn: UsbDeviceConnection,
    private val claimed: List<UsbInterface>,
    private val handle: Long,
    val rate: Int,
    val alt: AsAlt,
    private val desc: UacDescriptor,
) {
    @Volatile
    var alive = true
        private set

    private val thread = Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val result = UsbIsoNative.run(handle)
        if (result != 0) Log.w(TAG, "stream ended with $result")
        alive = false
    }, "UsbIso").apply { start() }

    val bytesPerFrame get() = alt.subslotSize * alt.channels

    fun write(data: ByteArray, off: Int, len: Int) = UsbIsoNative.write(handle, data, off, len)
    fun freeBytes() = UsbIsoNative.freeBytes(handle)
    fun framesPlayed() = UsbIsoNative.framesPlayed(handle)
    fun setPaused(paused: Boolean) = UsbIsoNative.setPaused(handle, paused)
    fun flush() = UsbIsoNative.flush(handle)

    private val lock = Any()
    private var closed = false

    /** DAC の音量(1/256 dB)。close 後は何もしない。 */
    fun setVolume(db256: Int) = synchronized(lock) {
        if (!closed) UsbDac.setVolume(conn, desc, db256)
    }

    fun close() {
        UsbVolume.attach(null, null, 0)
        Log.i(TAG, "close underruns=${UsbIsoNative.underruns(handle)}")
        UsbIsoNative.requestStop(handle)
        thread.join(2000)
        if (thread.isAlive) Log.w(TAG, "stream thread did not stop")
        else UsbIsoNative.destroy(handle)
        synchronized(lock) {
            closed = true
            for (i in claimed) conn.releaseInterface(i)
            conn.close()
        }
        alive = false
    }

    companion object {
        private const val TAG = "UsbOutput"

        /** 入力の 1 サンプルのビット数 [bits] 以上で、一番近い PCM の alt を選ぶ(ステレオのみ)。 */
        private fun pickAlt(d: UacDescriptor, bits: Int): AsAlt? =
            d.alts.filter { it.pcm && it.channels == 2 && it.bitResolution >= bits }
                .minByOrNull { it.bitResolution }

        /** [rate] / [bits] で出力できるなら、DAC を開いて返す。できなければ null(権限なし・非対応など)。 */
        fun open(context: Context, rate: Int, bits: Int): UsbOutput? {
            val device = UsbDac.findAudioDevice(context) ?: return null
            val usb = context.getSystemService(UsbManager::class.java)
            if (!usb.hasPermission(device)) return null
            val conn = usb.openDevice(device) ?: return null
            val claimed = mutableListOf<UsbInterface>()
            fun fail(msg: String): UsbOutput? {
                Log.w(TAG, msg)
                for (i in claimed) conn.releaseInterface(i)
                conn.close()
                return null
            }

            val desc = parseUac2(conn.rawDescriptors) ?: return fail("not UAC2")
            val alt = pickAlt(desc, bits) ?: return fail("no alt for ${bits}bit")
            if (alt.feedbackEndpoint == 0) return fail("no feedback endpoint (implicit feedback is unsupported)")
            val ifaces = (0 until device.interfaceCount).map { device.getInterface(it) }
            val ac = ifaces.first { it.id == desc.acInterface }
            val asAlt = ifaces.first { it.id == alt.interfaceNumber && it.alternateSetting == alt.altSetting }
            val asZero = ifaces.first { it.id == alt.interfaceNumber && it.alternateSetting == 0 }

            if (!conn.claimInterface(ac, true)) return fail("claim AC failed")
            claimed += ac
            if (!conn.claimInterface(asAlt, true)) return fail("claim AS failed")
            claimed += asAlt

            val ranges = UsbDac.queryRates(conn, desc)
            if (ranges.none { rate in it.first..it.second }) return fail("rate $rate unsupported: $ranges")

            // 音を出す前に DAC の音量を下げる。下げられないなら、最大音量で鳴りうるので USB 直結は使わない。
            val range = UsbDac.queryVolumeRange(conn, desc) ?: return fail("no DAC volume control (featureUnit=${desc.featureUnitId} ch=${desc.volumeChannels})")
            val volume = UsbVolume.snap(range, UsbVolume.startVolume())
            if (!UsbDac.setVolume(conn, desc, volume)) return fail("cannot set DAC volume")
            Log.i(TAG, "volume range=${range.min / 256.0}..${range.max / 256.0}dB set=${volume / 256.0}dB")

            // 帯域ゼロの alt 0 にしてからクロックを変える。
            conn.setInterface(asZero)
            if (!UsbDac.setRate(conn, desc, rate)) return fail("SET_CUR $rate failed")
            val now = UsbDac.getRate(conn, desc)
            if (now != rate) return fail("clock is $now, wanted $rate")
            if (!conn.setInterface(asAlt)) return fail("setInterface failed")

            val bpf = alt.subslotSize * alt.channels
            val interval = alt.interval
            val handle = UsbIsoNative.create(
                conn.fileDescriptor, alt.endpointAddress, alt.feedbackEndpoint,
                alt.maxPacketSize, interval, bpf, rate,
            )
            Log.i(TAG, "opened ${rate}Hz ${alt.bitResolution}bit/${alt.subslotSize * 8}bit-slot alt=${alt.altSetting}")
            return UsbOutput(conn, claimed, handle, rate, alt, desc).also { UsbVolume.attach(it, range, volume) }
        }
    }
}
