package com.example.myplayer.usb

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

/** USB DAC の音量(単位は 1/256 dB)。範囲と現在値。USB 直結で DAC が開いている間だけ non-null。 */
data class UsbVolumeState(val min: Int, val max: Int, val res: Int, val current: Int)

/**
 * USB 直結ではシステム音量が効かないので、DAC 自身の音量を持つ。
 * - 初期音量(設定。SharedPreferences `usb` の `initial_db256` に保存。既定 −30dB): DAC を開いたときの音量。
 * - 再生中のスライダーでの変更は、このプロセスが動いている間だけ有効(次回の起動は初期音量から)。ただし「音量を保持する」が ON なら保存して、次回もその音量で始める。
 *   保持は既定で OFF(DAC が最大音量で鳴らないようにするため)。
 */
object UsbVolume {
    const val DEFAULT_DB256 = -30 * 256
    const val INITIAL_MIN_DB256 = -60 * 256
    const val INITIAL_MAX_DB256 = 0
    private const val KEY_INITIAL = "initial_db256"
    private const val KEY_KEEP = "keep_volume"
    private const val KEY_ALLOW_FIXED = "allow_no_volume_dac"
    private const val KEY_DIRECT = "direct_output"
    private const val KEY_LAST = "last_db256"

    private val _state = MutableStateFlow<UsbVolumeState?>(null)
    val state: StateFlow<UsbVolumeState?> = _state

    private val _initial = MutableStateFlow(DEFAULT_DB256)
    val initial: StateFlow<Int> = _initial

    private val _keep = MutableStateFlow(false)
    val keep: StateFlow<Boolean> = _keep

    private val _direct = MutableStateFlow(false)

    /**
     * USB DAC への直接出力(ビットパーフェクト)を使うか。既定 OFF。
     * 直結では OS の音量が効かず、DAC 側の音量で鳴るので、使う人が自分で ON にする。OFF の間は DAC に触らない。
     */
    val directOutput: StateFlow<Boolean> = _direct

    private val _allowFixed = MutableStateFlow(false)
    /** DAC 自身の音量を操作できない機種(据え置き型の DAC / アンプなど)でも、USB 直結を使うか。既定 OFF。 */
    val allowFixed: StateFlow<Boolean> = _allowFixed

    private var prefs: SharedPreferences? = null
    private var output: UsbOutput? = null
    private var session: Int? = null // スライダーで変えた値。null なら初期音量
    private val io = Executors.newSingleThreadExecutor()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("usb", Context.MODE_PRIVATE)
        prefs = p
        _initial.value = p.getInt(KEY_INITIAL, DEFAULT_DB256).coerceIn(INITIAL_MIN_DB256, INITIAL_MAX_DB256)
        _keep.value = p.getBoolean(KEY_KEEP, false)
        _allowFixed.value = p.getBoolean(KEY_ALLOW_FIXED, false)
        _direct.value = p.getBoolean(KEY_DIRECT, false)
    }

    /** 保持する設定が ON で、保存された最後の音量があればそれ。なければ初期音量。 */
    private fun baseVolume(): Int {
        if (_keep.value) {
            val last = prefs?.getInt(KEY_LAST, Int.MIN_VALUE) ?: Int.MIN_VALUE
            if (last != Int.MIN_VALUE) return last
        }
        return _initial.value
    }

    /** DAC を開くときの音量。このプロセスでスライダーを動かしていればその値。 */
    fun startVolume(): Int = session ?: baseVolume()

    fun snap(range: UsbDac.VolumeRange, db256: Int): Int =
        range.min + (db256.coerceIn(range.min, range.max) - range.min) / range.res * range.res

    internal fun attach(out: UsbOutput?, range: UsbDac.VolumeRange?, current: Int) {
        output = out
        _state.value = if (out != null && range != null) UsbVolumeState(range.min, range.max, range.res, current) else null
    }

    private fun apply(db256: Int) {
        val s = _state.value ?: return
        val v = snap(UsbDac.VolumeRange(s.min, s.max, s.res), db256)
        _state.value = s.copy(current = v)
        val out = output ?: return
        io.execute { out.setVolume(v) }
    }

    /** 再生中のスライダー。「音量を保持する」が ON のときだけ保存する。 */
    fun set(db256: Int) {
        session = db256
        if (_keep.value) prefs?.edit()?.putInt(KEY_LAST, db256)?.apply()
        apply(db256)
    }

    fun setDirectOutput(enabled: Boolean) {
        _direct.value = enabled
        prefs?.edit()?.putBoolean(KEY_DIRECT, enabled)?.apply()
    }

    fun setAllowFixed(allow: Boolean) {
        _allowFixed.value = allow
        prefs?.edit()?.putBoolean(KEY_ALLOW_FIXED, allow)?.apply()
    }

    /** 「音量を保持する」。ON にしたとき、今のスライダーの値があれば保存する。OFF にすると保存済みの音量は捨てる。 */
    fun setKeep(keep: Boolean) {
        _keep.value = keep
        val e = prefs?.edit() ?: return
        e.putBoolean(KEY_KEEP, keep)
        if (!keep) e.remove(KEY_LAST) else session?.let { e.putInt(KEY_LAST, it) }
        e.apply()
    }

    /** 設定画面の初期音量(1 dB 刻み)。保存する。スライダーで変えていなければ、開いている DAC にもすぐ反映する。 */
    fun setInitial(db256: Int) {
        val v = (db256.coerceIn(INITIAL_MIN_DB256, INITIAL_MAX_DB256) / 256) * 256
        _initial.value = v
        prefs?.edit()?.putInt(KEY_INITIAL, v)?.apply()
        if (session == null) apply(baseVolume())
    }
}
