package net.minittu.rinplay

import android.content.Context

/** 画面の状態のうち、次回の起動でも引き継ぎたいもの。 */
class UiPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    /** 最後に開いていた上部のタブ。0 = ローカル、1 = DLNA。 */
    var lastTab: Int
        get() = prefs.getInt(KEY_TAB, TAB_LOCAL).takeIf { it in TAB_LOCAL..TAB_DLNA } ?: TAB_LOCAL
        set(value) {
            prefs.edit().putInt(KEY_TAB, value).apply()
        }

    companion object {
        const val TAB_LOCAL = 0
        const val TAB_DLNA = 1
        private const val KEY_TAB = "last_tab"
    }
}
