package net.minittu.rinplay.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

/** 接続中の USB DAC(UAC2)を探し、権限を取って開く。 */
object UsbDac {
    private const val TAG = "UsbDac"
    private const val ACTION_PERMISSION = "net.minittu.rinplay.USB_PERMISSION"

    fun findAudioDevice(context: Context): UsbDevice? {
        val usb = context.getSystemService(UsbManager::class.java)
        return usb.deviceList.values.firstOrNull { d ->
            (0 until d.interfaceCount).any {
                val i = d.getInterface(it)
                i.interfaceClass == UsbConstants.USB_CLASS_AUDIO && i.interfaceSubclass == 2
            }
        }
    }

    /** 権限があれば true。なければ要求ダイアログを出して false(許可後に onGranted が呼ばれる)。 */
    fun ensurePermission(context: Context, device: UsbDevice, onGranted: () -> Unit): Boolean {
        val usb = context.getSystemService(UsbManager::class.java)
        if (usb.hasPermission(device)) return true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                context.unregisterReceiver(this)
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) onGranted()
                else Log.w(TAG, "USB permission denied")
            }
        }
        val filter = IntentFilter(ACTION_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        val pi = PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_PERMISSION).setPackage(context.packageName),
            PendingIntent.FLAG_MUTABLE,
        )
        usb.requestPermission(device, pi)
        return false
    }

    fun open(context: Context, device: UsbDevice): UsbDeviceConnection? =
        context.getSystemService(UsbManager::class.java).openDevice(device)

    /** クロックソースの対応サンプルレート範囲(min, max, res)を返す。 */
    fun queryRates(conn: UsbDeviceConnection, d: UacDescriptor): List<Triple<Int, Int, Int>> {
        val wIndex = (d.clockSourceId shl 8) or d.acInterface
        fun get(len: Int): Pair<Int, ByteArray> {
            val b = ByteArray(len)
            // 0xA1 = device-to-host / class / interface。bRequest 2 = GET_RANGE。wValue = CS_SAM_FREQ_CONTROL(1) << 8
            return conn.controlTransfer(0xA1, 2, 1 shl 8, wIndex, b, len, 1000) to b
        }
        val head = get(2)
        if (head.first < 2) return emptyList()
        val count = (head.second[0].toInt() and 0xFF) or ((head.second[1].toInt() and 0xFF) shl 8)
        val (n, buf) = get(2 + 12 * count)
        fun i32(o: Int) = (buf[o].toInt() and 0xFF) or ((buf[o + 1].toInt() and 0xFF) shl 8) or
            ((buf[o + 2].toInt() and 0xFF) shl 16) or ((buf[o + 3].toInt() and 0xFF) shl 24)
        return (0 until count).takeWhile { 2 + it * 12 + 12 <= n }
            .map { Triple(i32(2 + it * 12), i32(6 + it * 12), i32(10 + it * 12)) }
    }

    /** クロックのサンプルレートを設定する(SET_CUR)。 */
    fun setRate(conn: UsbDeviceConnection, d: UacDescriptor, rate: Int): Boolean {
        val b = ByteArray(4) { (rate shr (8 * it)).toByte() }
        // 0x21 = host-to-device / class / interface。bRequest 1 = SET_CUR。
        return conn.controlTransfer(0x21, 1, 1 shl 8, (d.clockSourceId shl 8) or d.acInterface, b, 4, 1000) == 4
    }

    /** クロックの現在のサンプルレート(GET_CUR)。失敗は -1。 */
    fun getRate(conn: UsbDeviceConnection, d: UacDescriptor): Int {
        val b = ByteArray(4)
        val n = conn.controlTransfer(0xA1, 1, 1 shl 8, (d.clockSourceId shl 8) or d.acInterface, b, 4, 1000)
        if (n != 4) return -1
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
    }

    /** DAC の音量の範囲。単位は 1/256 dB。 */
    data class VolumeRange(val min: Int, val max: Int, val res: Int)

    private fun volumeChannels(d: UacDescriptor) = if (0 in d.volumeChannels) listOf(0) else d.volumeChannels

    /** Feature Unit の VOLUME の範囲(GET_RANGE)。音量を操作できなければ null。 */
    fun queryVolumeRange(conn: UsbDeviceConnection, d: UacDescriptor): VolumeRange? {
        if (d.featureUnitId < 0 || d.volumeChannels.isEmpty()) return null
        val ch = volumeChannels(d).first()
        val r = ByteArray(8)
        // wNumSubRanges(2) + min(2) + max(2) + res(2)。値は符号付き 16bit で 1/256 dB 単位。
        val n = conn.controlTransfer(0xA1, 2, (2 shl 8) or ch /* CS_VOLUME_CONTROL */, (d.featureUnitId shl 8) or d.acInterface, r, r.size, 1000)
        if (n < 8) return null
        fun s16(o: Int) = ((r[o].toInt() and 0xFF) or (r[o + 1].toInt() shl 8)).toShort().toInt()
        return VolumeRange(s16(2), s16(4), s16(6).coerceAtLeast(1))
    }

    /** DAC 自身の音量を [db256](1/256 dB)にする。ビットは変えない。 */
    fun setVolume(conn: UsbDeviceConnection, d: UacDescriptor, db256: Int): Boolean {
        val b = byteArrayOf(db256.toByte(), (db256 shr 8).toByte())
        return volumeChannels(d).all {
            conn.controlTransfer(0x21, 1, (2 shl 8) or it, (d.featureUnitId shl 8) or d.acInterface, b, 2, 1000) == 2
        }
    }

    /** 各チャンネルの現在の音量(GET_CUR)。(チャンネル, 1/256 dB)の組。読めなければ null。 */
    fun getVolumes(conn: UsbDeviceConnection, d: UacDescriptor): List<Pair<Int, Int>>? {
        val result = mutableListOf<Pair<Int, Int>>()
        for (ch in volumeChannels(d)) {
            val b = ByteArray(2)
            // 0xA1 = device-to-host / class / interface。bRequest 1 = GET_CUR。
            val n = conn.controlTransfer(0xA1, 1, (2 shl 8) or ch, (d.featureUnitId shl 8) or d.acInterface, b, 2, 1000)
            if (n != 2) return null
            result += ch to ((b[0].toInt() and 0xFF) or (b[1].toInt() shl 8)).toShort().toInt()
        }
        return result
    }

    /** [getVolumes] で読んだ音量に戻す。 */
    fun restoreVolumes(conn: UsbDeviceConnection, d: UacDescriptor, volumes: List<Pair<Int, Int>>): Boolean =
        volumes.all { (ch, db256) ->
            val b = byteArrayOf(db256.toByte(), (db256 shr 8).toByte())
            conn.controlTransfer(0x21, 1, (2 shl 8) or ch, (d.featureUnitId shl 8) or d.acInterface, b, 2, 1000) == 2
        }

    /** DAC があれば、権限を確認する。権限があれば(または許可されたあとで)[onReady] を呼ぶ。 */
    fun prepare(context: Context, onReady: () -> Unit = {}) {
        val device = findAudioDevice(context) ?: return
        if (ensurePermission(context, device, onReady)) {
            onReady()
        } else {
            Log.i(TAG, "requesting USB permission for ${device.productName}")
        }
    }
}
