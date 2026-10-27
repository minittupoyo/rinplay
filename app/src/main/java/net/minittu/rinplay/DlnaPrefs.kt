package net.minittu.rinplay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** DLNA 画面でたどったフォルダの 1 階層。 */
data class Crumb(val id: String, val title: String)

/** 最後に開いていたサーバーとフォルダの階層。 */
data class DlnaPlace(val serverUdn: String, val path: List<Crumb>)

class DlnaPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("dlna", Context.MODE_PRIVATE)

    fun load(): DlnaPlace? {
        val udn = prefs.getString(KEY_UDN, null) ?: return null
        val path = runCatching {
            val array = JSONArray(prefs.getString(KEY_PATH, "[]"))
            List(array.length()) { i ->
                val item = array.getJSONObject(i)
                Crumb(item.getString("id"), item.getString("title"))
            }
        }.getOrDefault(emptyList())
        return DlnaPlace(udn, path)
    }

    fun save(place: DlnaPlace) {
        val array = JSONArray()
        place.path.forEach { array.put(JSONObject().put("id", it.id).put("title", it.title)) }
        prefs.edit()
            .putString(KEY_UDN, place.serverUdn)
            .putString(KEY_PATH, array.toString())
            .apply()
    }

    private companion object {
        const val KEY_UDN = "server_udn"
        const val KEY_PATH = "path"
    }
}
