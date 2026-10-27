package net.minittu.rinplay.dlna

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * DLNA の曲に埋め込まれたジャケットを、音声ファイルの先頭から読む。
 *
 * サーバー(MiniDLNA など)が返す upnp:albumArtURI は 160×160 のサムネイル(JPEG_TN)なので、
 * 全画面(約 950px)では 6 倍に引き伸ばされてぼやける。ファイルには 1200×1200 が入っていることが多い。
 * 先頭 64KB を読んで絵の位置と長さを確かめ、その範囲だけを Range で取り直す(実測で 206 が返る)。
 * 曲ごとに覚えておき、次からは取りに行かない。
 */
object EmbeddedArtwork {
    private const val TAG = "Dlna"

    /** まず読む先頭の長さ。この中にタグやブロックの見出しが入っていれば、絵の位置と大きさが分かる。 */
    private const val PREFIX_BYTES = 64 * 1024

    /** 取り込む絵の上限。壊れた大きさで巨大な範囲を取りに行かないため(いまの手持ちは 100〜450KB)。 */
    private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024

    /** 覚えておく合計。数十曲ぶんで頭打ちにする。 */
    private const val MAX_CACHE_BYTES = 8 * 1024 * 1024

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 10_000

    private val _state = MutableStateFlow<Map<String, ByteArray>>(emptyMap())

    /** mediaId → ファイルから取り出したジャケット。取り出せなかった曲は入らない。 */
    val state: StateFlow<Map<String, ByteArray>> = _state

    /**
     * 取りに行く仕事は、この object が持つ。呼び出し元(一覧の行など)が消えても取りに行くのを止めないため
     * (行はすぐ破棄されることがあり、そこに紐づけると読む途中で消えて、いつまでも絵が出ない)。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 一度に取りに行く数。行が何十と並んでも、サーバーを一斉に叩かないようにする。 */
    private val permits = Semaphore(4)

    private val attempted = mutableSetOf<String>()
    private val cache = LinkedHashMap<String, ByteArray>()
    private var cachedBytes = 0

    /**
     * [mediaUrl] の先頭から埋め込みジャケットを取りに行く(結果は [state] に出る)。
     * 取れなければ何もしない(呼び出し側は、これまでどおりサーバーのアートのままにする)。
     * 同じ曲は一度しか試さない。http(s) 以外(端末内のファイルなど)は対象外。
     */
    fun request(mediaId: String, mediaUrl: String) {
        if (!mediaUrl.startsWith("http://") && !mediaUrl.startsWith("https://")) return
        if (!synchronized(attempted) { attempted.add(mediaId) }) return
        scope.launch {
            val image = permits.withPermit {
                runCatching { fetch(mediaUrl) }
                    .onFailure { Log.i(TAG, "埋め込みジャケットを読めませんでした ($mediaId): $it") }
                    .getOrNull()
            } ?: return@launch
            put(mediaId, image)
            Log.i(TAG, "埋め込みジャケットを使います: $mediaId (${image.size} bytes)")
        }
    }

    private fun fetch(url: String): ByteArray? {
        val prefix = readRange(url, 0, PREFIX_BYTES - 1L) ?: return null
        return when (val probe = ArtworkParser.probe(prefix)) {
            is ArtworkParser.Probe.Found -> probe.image
            is ArtworkParser.Probe.Need -> {
                val length = probe.endInclusive - probe.start + 1
                if (length > MAX_IMAGE_BYTES) null else readRange(url, probe.start, probe.endInclusive)?.let(probe.parse)
            }
            ArtworkParser.Probe.None -> null
        }
    }

    /**
     * Range で [start]..[endInclusive] を読む。
     * Range を無視して全体を返してくるサーバーには、先頭を読むときだけ付き合う(読む量は [endInclusive] で頭打ちにする)。
     */
    private fun readRange(url: String, start: Long, endInclusive: Long): ByteArray? {
        val connection = URL(url).openConnection() as? HttpURLConnection ?: return null
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Range", "bytes=$start-$endInclusive")
            val code = connection.responseCode
            val partial = code == HttpURLConnection.HTTP_PARTIAL
            if (!partial && !(code == HttpURLConnection.HTTP_OK && start == 0L)) return null
            val limit = (endInclusive - start + 1).coerceAtMost(MAX_IMAGE_BYTES.toLong()).toInt()
            connection.inputStream.use { input ->
                val out = ByteArrayOutputStream(minOf(limit, 64 * 1024))
                val buffer = ByteArray(16 * 1024)
                while (out.size() < limit) {
                    val read = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
                return out.toByteArray()
            }
        } catch (e: IOException) {
            Log.i(TAG, "読み込みに失敗: $url ($start-$endInclusive): $e")
            return null
        } finally {
            connection.disconnect()
        }
    }

    private fun put(mediaId: String, image: ByteArray) {
        synchronized(cache) {
            cache.put(mediaId, image)?.let { cachedBytes -= it.size }
            cachedBytes += image.size
            val iterator = cache.entries.iterator()
            while (cachedBytes > MAX_CACHE_BYTES && iterator.hasNext()) {
                cachedBytes -= iterator.next().value.size
                iterator.remove()
            }
        }
        _state.value = synchronized(cache) { cache.toMap() }
    }
}
