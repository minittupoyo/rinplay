package net.minittu.rinplay.lyrics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** 歌詞の検索に使う曲の情報。ファイルのメタデータ(タグ)から作る。 */
data class LyricsQuery(
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    /** 曲の長さ(秒)。分からないときは null(LRCLIB の一致判定に使う)。 */
    val durationSec: Int? = null,
)

/** 同期つき歌詞(LRC)の 1 行。 */
data class LrcLine(val timeMs: Long, val text: String)

/** LRCLIB から取得した歌詞。 */
data class Lyrics(
    val id: Long,
    val trackName: String,
    val artistName: String,
    val albumName: String,
    val durationSec: Int,
    val instrumental: Boolean,
    val plain: String?,
    val synced: List<LrcLine>,
) {
    /** 歌詞が無い(インストゥルメンタル、または本文が空)。 */
    val isEmpty: Boolean get() = instrumental || (plain.isNullOrBlank() && synced.isEmpty())

    /** 一覧に出す「曲名 — アーティスト」。 */
    val label: String
        get() = listOf(trackName, artistName).filter { it.isNotEmpty() }.joinToString(" — ")
}

/** 通信できなかったとき(サーバーの不調・圏外など)。「歌詞が無い」とは区別する。 */
class LyricsUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * LRCLIB(https://lrclib.net)から歌詞を取る。
 *
 * - [find]: メタデータで 1 件探す。まず署名つきの `/api/get`(長さが近いものだけ返る)、
 *   見つからなければ `/api/search` の 1 件目を使う。無ければ null。
 * - [search]: 曲名・アーティストを指定して候補を一覧する(見つからないときの手動検索用)。
 * - [byId]: 候補を選んだときに、その 1 件を取る。
 *
 * 結果は曲(mediaId)ごとに覚えておき、次に同じ曲を再生したときは通信しない。
 * アプリを終了しても残るよう、`filesDir/lyrics` に置く。
 */
class LyricsRepository(context: Context) {

    private val cacheDir = File(context.filesDir, "lyrics")

    /** 曲(mediaId) → 歌詞。見つからなかった曲は [notFound] に入れる。 */
    private val memory = mutableMapOf<String, Lyrics>()
    private val notFound = mutableSetOf<String>()
    private val offsets = mutableMapOf<String, Long>()

    /**
     * メタデータで歌詞を探す。見つからなければ null。
     * 通信できなかったときは [LyricsUnavailableException] を投げる。
     */
    suspend fun find(cacheKey: String, query: LyricsQuery): Lyrics? = withContext(Dispatchers.IO) {
        memory[cacheKey]?.let { return@withContext it }
        if (cacheKey in notFound) return@withContext null
        readCache(cacheKey)?.let { return@withContext it.also { found -> memory[cacheKey] = found } }

        val record = try {
            get(query) ?: searchFirst(query)
        } catch (e: LyricsUnavailableException) {
            // 取れなかった理由(圏外・サーバーの不調)を残す。次の機会にまた試す。
            Log.w(TAG, "歌詞を取れません (${query.title} / ${query.artist}): ${e.message}")
            throw e
        }
        if (record == null) {
            notFound += cacheKey
            Log.i(TAG, "歌詞なし: ${query.title} / ${query.artist}")
            return@withContext null
        }
        Log.i(TAG, "歌詞あり: ${record.label} (id=${record.id}, 同期=${record.synced.size} 行)")
        writeCache(cacheKey, record)
        memory[cacheKey] = record
        record
    }

    /** 手で選んだ歌詞を、その曲(mediaId)のものとして覚える。次に再生したときも同じ歌詞を出す。 */
    suspend fun save(cacheKey: String, lyrics: Lyrics) = withContext(Dispatchers.IO) {
        memory[cacheKey] = lyrics
        notFound -= cacheKey
        writeCache(cacheKey, lyrics)
    }

    /** 曲(mediaId)ごとの同期のずれ(ミリ秒)。正なら歌詞を早く出す。無ければ 0。 */
    suspend fun offsetMs(cacheKey: String): Long = withContext(Dispatchers.IO) {
        offsets.getOrPut(cacheKey) {
            runCatching { offsetFile(cacheKey).takeIf { it.isFile }?.readText()?.trim()?.toLong() }.getOrNull() ?: 0L
        }
    }

    suspend fun saveOffsetMs(cacheKey: String, offsetMs: Long) = withContext(Dispatchers.IO) {
        offsets[cacheKey] = offsetMs
        runCatching {
            cacheDir.mkdirs()
            val file = offsetFile(cacheKey)
            if (offsetMs == 0L) file.delete() else file.writeText(offsetMs.toString())
        }.onFailure { Log.w(TAG, "オフセットを保存できません: $it") }
        Unit
    }

    /** 曲名・アーティストで候補を探す。見つからなければ空。 */
    suspend fun search(query: LyricsQuery): List<Lyrics> = withContext(Dispatchers.IO) {
        val params = buildList {
            add("track_name" to query.title)
            query.artist?.let { add("artist_name" to it) }
            query.album?.let { add("album_name" to it) }
        }
        request("/api/search", params)?.let { parseList(it) }.orEmpty()
    }

    /** 候補を選んだときに、その 1 件を取る。 */
    suspend fun byId(id: Long): Lyrics? = withContext(Dispatchers.IO) {
        request("/api/get/$id", emptyList())?.let { parseRecord(JSONObject(it)) }
    }

    // --- LRCLIB の呼び出し ---

    /**
     * `/api/get`。曲名とアーティストは必須で、長さが ±2 秒以内のときだけ返る。
     * アーティストが分からない曲では探しようがないので、null を返す(呼び出し側が `/api/search` に回す)。
     */
    private fun get(query: LyricsQuery): Lyrics? {
        val artist = query.artist ?: return null
        val params = buildList {
            add("track_name" to query.title)
            add("artist_name" to artist)
            query.album?.let { add("album_name" to it) }
            query.durationSec?.let { add("duration" to it.toString()) }
        }
        return request("/api/get", params)?.let { parseRecord(JSONObject(it)) }
    }

    /** `/api/search` の 1 件目。長さの一致を見ないので、`/api/get` で外れたときの受け皿。 */
    private fun searchFirst(query: LyricsQuery): Lyrics? = searchBlocking(query).firstOrNull()

    private fun searchBlocking(query: LyricsQuery): List<Lyrics> {
        val params = buildList {
            add("track_name" to query.title)
            query.artist?.let { add("artist_name" to it) }
        }
        return request("/api/search", params)?.let { parseList(it) }.orEmpty()
    }

    /**
     * GET して本文を返す。404 は null(見つからない)。
     * つながらない・サーバーが応えないときは [LyricsUnavailableException]。
     *
     * 429 / 503(混み合っている)は `Retry-After` の秒数だけ待って 1 度だけ試し直す
     * (LRCLIB は待たずに投げ直すと制限が強まるので、その指示に従う)。
     */
    private fun request(path: String, params: List<Pair<String, String>>): String? {
        val query = params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val url = URL(BASE_URL + path + if (query.isEmpty()) "" else "?$query")

        var tried = 0
        while (true) {
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            try {
                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> return connection.inputStream.bufferedReader().use { it.readText() }
                    HttpURLConnection.HTTP_NOT_FOUND -> return null
                    RATE_LIMITED, SERVER_BUSY -> {
                        val waitMs = retryAfterMs(connection, code)
                        // 長く待たされるなら、その場で待たずに「混み合っている」と伝える。
                        if (tried == 0 && waitMs <= MAX_RETRY_WAIT_MS) {
                            tried++
                            Log.i(TAG, "LRCLIB が混み合っている(HTTP $code)。${waitMs}ms 待って再試行")
                            Thread.sleep(waitMs)
                            continue
                        }
                        throw LyricsUnavailableException("LRCLIB が混み合っています。しばらくしてから試してください (HTTP $code)")
                    }
                    else -> throw LyricsUnavailableException("LRCLIB の応答が異常です (HTTP $code)")
                }
            } catch (e: IOException) {
                if (e is LyricsUnavailableException) throw e
                throw LyricsUnavailableException("LRCLIB につながりませんでした", e)
            } finally {
                connection.disconnect()
            }
        }
    }

    /** `Retry-After` の秒数(なければ既定値)。同時に再試行が重ならないよう、少し揺らす。 */
    private fun retryAfterMs(connection: HttpURLConnection, code: Int): Long {
        val seconds = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
        val base = seconds?.times(1000) ?: if (code == RATE_LIMITED) 5_000L else 1_000L
        return base + (base * JITTER_RATIO * Math.random()).toLong()
    }

    // --- JSON ---

    private fun parseList(json: String): List<Lyrics> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { i ->
            runCatching { parseRecord(array.getJSONObject(i)) }.getOrNull()
        }
    }

    private fun parseRecord(json: JSONObject): Lyrics = Lyrics(
        id = json.optLong("id"),
        trackName = json.optString("trackName"),
        artistName = json.optString("artistName"),
        albumName = json.optString("albumName"),
        durationSec = json.optDouble("duration", 0.0).toInt(),
        instrumental = json.optBoolean("instrumental"),
        plain = json.optStringOrNull("plainLyrics"),
        synced = parseLrc(json.optStringOrNull("syncedLyrics").orEmpty()),
    )

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    // --- 曲ごとのキャッシュ ---

    private fun cacheFile(cacheKey: String) = File(cacheDir, "${cacheKey.sha1()}.json")

    private fun offsetFile(cacheKey: String) = File(cacheDir, "${cacheKey.sha1()}.offset")

    private fun readCache(cacheKey: String): Lyrics? {
        val file = cacheFile(cacheKey)
        if (!file.isFile) return null
        return runCatching { parseRecord(JSONObject(file.readText())) }
            .onFailure { file.delete() } // 壊れていたら捨てて、取り直す
            .getOrNull()
    }

    private fun writeCache(cacheKey: String, lyrics: Lyrics) {
        runCatching {
            cacheDir.mkdirs()
            cacheFile(cacheKey).writeText(lyrics.toJson().toString())
        }.onFailure { Log.w(TAG, "歌詞を保存できません: $it") }
    }

    private fun Lyrics.toJson() = JSONObject()
        .put("id", id)
        .put("trackName", trackName)
        .put("artistName", artistName)
        .put("albumName", albumName)
        .put("duration", durationSec)
        .put("instrumental", instrumental)
        .put("plainLyrics", plain)
        .put("syncedLyrics", synced.joinToString("") { "[${it.timeMs.toLrcTime()}]${it.text}\n" })

    private companion object {
        const val TAG = "rinplay-lyrics"
        const val BASE_URL = "https://lrclib.net"
        const val TIMEOUT_MS = 10_000
        const val RATE_LIMITED = 429
        const val SERVER_BUSY = 503
        /** これより長く待たされるなら、その場では待たない。 */
        const val MAX_RETRY_WAIT_MS = 10_000L
        /** 再試行の待ち時間に足す揺らぎ(同期した再試行の波を避ける)。 */
        const val JITTER_RATIO = 0.3
        // LRCLIB はクライアントの識別を求めている(アプリ名・版・URL)。
        const val USER_AGENT = "RinPlay v0.1 (https://github.com/minittupoyo/rinplay)"
    }
}

private fun String.sha1(): String =
    MessageDigest.getInstance("SHA-1").digest(toByteArray()).joinToString("") { "%02x".format(it) }

/** ミリ秒 → `mm:ss.xx`(保存用)。 */
private fun Long.toLrcTime(): String {
    val ms = coerceAtLeast(0)
    return "%02d:%02d.%02d".format(ms / 60_000, ms / 1000 % 60, ms % 1000 / 10)
}

private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
private val OFFSET_TAG = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

/**
 * LRC を解釈する。1 行に複数の時刻(`[00:10.00][01:20.00] 同じ歌詞`)が付くことがあり、
 * その場合は行ごとに分ける。`[offset:±ms]` があれば全行に足す。
 */
fun parseLrc(text: String): List<LrcLine> {
    if (text.isBlank()) return emptyList()
    var offsetMs = 0L
    val lines = mutableListOf<LrcLine>()

    text.lineSequence().forEach { raw ->
        OFFSET_TAG.find(raw)?.let {
            offsetMs = it.groupValues[1].toLongOrNull() ?: 0L
            return@forEach
        }
        val tags = TIME_TAG.findAll(raw).toList()
        if (tags.isEmpty()) return@forEach // [ar:] などのタグ行、または本文だけの行
        val content = raw.substring(tags.last().range.last + 1).trim()
        if (content.isEmpty()) return@forEach // 間奏の空行は、行として出さない
        tags.forEach { tag ->
            val minutes = tag.groupValues[1].toLong()
            val seconds = tag.groupValues[2].toLong()
            // 小数部は桁数で単位が変わる(1 桁 = 100ms、2 桁 = 10ms、3 桁 = 1ms)。
            val fraction = tag.groupValues[3]
            val millis = when (fraction.length) {
                0 -> 0L
                1 -> fraction.toLong() * 100
                2 -> fraction.toLong() * 10
                else -> fraction.toLong()
            }
            lines += LrcLine(minutes * 60_000 + seconds * 1000 + millis + offsetMs, content)
        }
    }
    return lines.sortedBy { it.timeMs }
}
