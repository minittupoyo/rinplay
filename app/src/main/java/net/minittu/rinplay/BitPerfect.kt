package net.minittu.rinplay

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.util.Log

/** ビットパーフェクト出力(Android 14+)の可否判定と有効化。 */
object BitPerfect {
    private const val TAG = "BitPerfect"

    /** [apply] が成功したときの状態の文字列(画面の再生情報は、これで OS のビットパーフェクトかを判定する)。 */
    const val STATUS_ENABLED = "有効"

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    // 整数 PCM のうち情報量の多い順。float は USB DAC が受けないことが多いので対象外。
    private val encodingPreference = listOf(
        AudioFormat.ENCODING_PCM_32BIT,
        AudioFormat.ENCODING_PCM_24BIT_PACKED,
        AudioFormat.ENCODING_PCM_16BIT,
    )

    /** 状態の説明文を返す。画面表示とログに使う。 */
    fun apply(context: Context, sampleRateHz: Int): String {
        if (Build.VERSION.SDK_INT < 34) return "非対応(Android 14 以降が必要)"
        val am = context.getSystemService(AudioManager::class.java)
        val devices = am.getAudioDevicesForAttributes(attributes)
        if (devices.isEmpty()) return "出力デバイスなし"

        for (device in devices) {
            val supported = am.getSupportedMixerAttributes(device)
            Log.i(TAG, "device=${device.describe()} supported=${supported.map { it.describe() }}")

            val atRate = supported.filter { it.format.sampleRate == sampleRateHz }
            val pick = encodingPreference.firstNotNullOfOrNull { enc ->
                atRate.firstOrNull { it.format.encoding == enc }
            } ?: continue

            // 一覧の behavior は default で返るため、BIT_PERFECT を指定して組み直す。
            val wanted = AudioMixerAttributes.Builder(pick.format)
                .setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
                .build()
            val ok = am.setPreferredMixerAttributes(attributes, device, wanted)
            val now = am.getPreferredMixerAttributes(attributes, device)
            Log.i(TAG, "set(${wanted.describe()}) -> $ok, now=${now?.describe()}")
            return if (ok) STATUS_ENABLED else "要求失敗"
        }
        Log.i(TAG, "no bit-perfect profile: devices=${devices.joinToString { it.describe() }} rate=${sampleRateHz}Hz")
        return "非対応(この出力機器は未対応)"
    }

    fun clear(context: Context) {
        if (Build.VERSION.SDK_INT < 34) return
        val am = context.getSystemService(AudioManager::class.java)
        for (device in am.getAudioDevicesForAttributes(attributes)) {
            am.clearPreferredMixerAttributes(attributes, device)
        }
    }
}

private fun AudioDeviceInfo.describe() = "type=$type name=$productName"

private fun AudioMixerAttributes.describe(): String {
    val f: AudioFormat = format
    val behavior = if (mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) "bit-perfect" else "default"
    return "${f.sampleRate}Hz enc=${f.encoding} ch=${f.channelMask} $behavior"
}
