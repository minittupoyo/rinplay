package com.example.myplayer.usb

import android.content.Context
import android.hardware.usb.UsbDevice

/**
 * 直結のために下げた DAC の音量の「元の値」を、端末に保存しておく。
 * 閉じるときに元に戻すが、アプリが強制終了などで閉じる処理を通らなかったとき、DAC の音量が下がったまま残る。
 * 保存が残っていれば、次に開くとき(または起動時)に、保存した値に戻せる。
 *
 * キーはデバイスごと(`deviceName` は挿し直すと変わるので、挿し直された(音量が初期値に戻った)DAC の古い保存は
 * 使われない)。
 */
object UsbVolumeGuard {
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("usb", Context.MODE_PRIVATE)

    fun key(device: UsbDevice) = "lowered_${device.vendorId}_${device.productId}_${device.deviceName}"

    /** 音量を下げる前に呼ぶ。下げた直後に強制終了されても残るよう、同期で書く。 */
    fun mark(context: Context, key: String, volumes: List<Pair<Int, Int>>) {
        prefs(context).edit().putString(key, volumes.joinToString(",") { "${it.first}:${it.second}" }).commit()
    }

    /** 保存された元の音量。なければ null。 */
    fun get(context: Context, key: String): List<Pair<Int, Int>>? {
        val s = prefs(context).getString(key, null) ?: return null
        return s.split(",").mapNotNull {
            val p = it.split(":")
            val ch = p.getOrNull(0)?.toIntOrNull()
            val v = p.getOrNull(1)?.toIntOrNull()
            if (ch != null && v != null) ch to v else null
        }.takeIf { it.isNotEmpty() }
    }

    /** 元に戻せたら消す。 */
    fun clear(context: Context, key: String) {
        prefs(context).edit().remove(key).apply()
    }
}
