package net.minittu.rinplay.usb

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
    /** 開く前の DAC の音量。閉じるときに戻す(直結で下げた音量が、OS 経由の再生に残らないように)。 */
    private val originalVolumes: List<Pair<Int, Int>>?,
    private val appContext: Context,
    private val guardKey: String,
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
            // ドライバを付け直す前に、DAC の音量を元に戻す(claim している間しか操作できない)。
            originalVolumes?.let {
                val ok = UsbDac.restoreVolumes(conn, desc, it)
                Log.i(TAG, "restore DAC volume to ${it.map { v -> v.second / 256.0 }}dB -> $ok")
                if (ok) UsbVolumeGuard.clear(appContext, guardKey)
            }
            releaseAll(conn, claimed)
            conn.close()
        }
        alive = false
    }

    companion object {
        private const val TAG = "UsbOutput"

        /** claim を解き、切り離したカーネルのドライバを付け直す(OS の音声出力を戻す)。接続は閉じない。 */
        private fun releaseAll(conn: UsbDeviceConnection, claimed: List<UsbInterface>) {
            val fd = conn.fileDescriptor
            // AS → AC の順に解放する。AC を解放した時点で、カーネルのドライバ(snd-usb-audio)が自動で付き直り、
            // そのとき AS も掴むので、AS を先に空けておく。AC を先に解放すると、AS を握られたまま付き直って、
            // カードが不完全になる(OS 経由の再生が途切れる)。
            for (i in claimed.asReversed()) conn.releaseInterface(i)
            // 自動で付き直らなかったときのための保険(付いていれば EBUSY で何もしない)。
            for (i in claimed) {
                Log.i(TAG, "reattach kernel driver if=${i.id} -> ${UsbIsoNative.attachKernelDriver(fd, i.id)}")
            }
        }

        /** 入力の 1 サンプルのビット数 [bits] 以上で、一番近い PCM の alt を選ぶ(ステレオのみ)。 */
        private fun pickAlt(d: UacDescriptor, bits: Int): AsAlt? =
            d.alts.filter { it.pcm && it.channels == 2 && it.bitResolution >= bits }
                .minByOrNull { it.bitResolution }

        /** DAC の操作(開く・回復)を同時に行わないための鍵。 */
        private val deviceLock = Any()

        /**
         * 前回、直結のまま閉じる処理を通らずに終わり、DAC の音量が下がったままなら、元に戻す。
         * 権限がなければ何もしない(権限は、挿している間は残る)。DAC のドライバを一度切り離して付け直す。
         */
        fun recoverVolume(context: Context) = synchronized(deviceLock) {
            val device = UsbDac.findAudioDevice(context) ?: return@synchronized
            val usb = context.getSystemService(UsbManager::class.java)
            if (!usb.hasPermission(device)) return@synchronized
            val key = UsbVolumeGuard.key(device)
            val saved = UsbVolumeGuard.get(context, key) ?: return@synchronized
            val conn = usb.openDevice(device) ?: return@synchronized
            var ok = false
            try {
                val desc = parseUac2(conn.rawDescriptors)
                val ac = (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull { it.id == desc?.acInterface }
                if (desc != null && ac != null && conn.claimInterface(ac, true)) {
                    ok = UsbDac.restoreVolumes(conn, desc, saved)
                    releaseAll(conn, listOf(ac))
                }
            } finally {
                conn.close()
            }
            Log.i(TAG, "recovered DAC volume to ${saved.map { it.second / 256.0 }}dB -> $ok")
            if (ok) UsbVolumeGuard.clear(context, key)
        }

        /** [rate] / [bits] で出力できるなら、DAC を開いて返す。できなければ null(権限なし・非対応など)。 */
        fun open(context: Context, rate: Int, bits: Int): UsbOutput? = synchronized(deviceLock) { openLocked(context, rate, bits) }

        private fun openLocked(context: Context, rate: Int, bits: Int): UsbOutput? {
            val device = UsbDac.findAudioDevice(context) ?: return null
            val usb = context.getSystemService(UsbManager::class.java)
            if (!usb.hasPermission(device)) return null
            val conn = usb.openDevice(device) ?: return null
            val guardKey = UsbVolumeGuard.key(device)
            val claimed = mutableListOf<UsbInterface>()
            // 音量を下げたあとに失敗したら、元に戻す。
            var loweredDesc: UacDescriptor? = null
            var loweredFrom: List<Pair<Int, Int>>? = null
            fun fail(msg: String): UsbOutput? {
                Log.w(TAG, msg)
                val d = loweredDesc
                val o = loweredFrom
                if (d != null && o != null && UsbDac.restoreVolumes(conn, d, o)) UsbVolumeGuard.clear(context, guardKey)
                releaseAll(conn, claimed)
                conn.close()
                return null
            }

            val desc = parseUac2(conn.rawDescriptors) ?: return fail("not UAC2")
            val alt = pickAlt(desc, bits) ?: return fail("no alt for ${bits}bit")
            // 音量を操作できない DAC は、最大音量で鳴りうるので、設定で許可されたときだけ使う(DAC 側のダイヤルなどで調整する機種向け)。
            // claim(カーネルのドライバの切り離し)の前に判定する。
            val hasVolume = desc.featureUnitId >= 0 && desc.volumeChannels.isNotEmpty()
            if (!hasVolume && !UsbVolume.allowFixed.value) return fail("no DAC volume control (featureUnit=${desc.featureUnitId})")
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
            // 音量を操作できない DAC(許可された場合)は、アプリでは音量を触らない(スライダーも出さない)。
            var range: UsbDac.VolumeRange? = null
            var original: List<Pair<Int, Int>>? = null
            var volume = 0
            if (hasVolume) {
                val r = UsbDac.queryVolumeRange(conn, desc) ?: return fail("no DAC volume control (featureUnit=${desc.featureUnitId} ch=${desc.volumeChannels})")
                // 前回、閉じる処理を通らずに終わっていたら、DAC の音量は下がったまま(いま読める値は元の値ではない)。
                // 保存してある元の値を使う。保存がなければ、いまの値を元の値として保存する(下げる前に、同期で)。
                val saved = UsbVolumeGuard.get(context, guardKey)
                original = saved ?: UsbDac.getVolumes(conn, desc)
                if (saved != null) Log.w(TAG, "previous session did not restore the DAC volume; using the saved original ${saved.map { it.second / 256.0 }}")
                else original?.let { UsbVolumeGuard.mark(context, guardKey, it) }
                loweredDesc = desc
                loweredFrom = original
                volume = UsbVolume.snap(r, UsbVolume.startVolume())
                if (!UsbDac.setVolume(conn, desc, volume)) return fail("cannot set DAC volume")
                range = r
                Log.i(TAG, "volume range=${r.min / 256.0}..${r.max / 256.0}dB set=${volume / 256.0}dB (original=${original?.map { it.second / 256.0 }})")
            } else {
                Log.i(TAG, "DAC has no volume control; the volume is set on the DAC itself")
            }

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
            return UsbOutput(conn, claimed, handle, rate, alt, desc, original, context.applicationContext, guardKey).also { UsbVolume.attach(it, range, volume) }
        }
    }
}
