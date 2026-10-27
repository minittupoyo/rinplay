package com.example.myplayer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.wifi.WifiManager
import android.os.IBinder
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import com.example.myplayer.dlna.MyUpnpService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jupnp.android.AndroidUpnpService
import org.jupnp.model.action.ActionInvocation
import org.jupnp.model.message.UpnpResponse
import org.jupnp.model.meta.Device
import org.jupnp.model.meta.RemoteDevice
import org.jupnp.model.meta.Service
import org.jupnp.model.types.UDAServiceType
import org.jupnp.registry.DefaultRegistryListener
import org.jupnp.registry.Registry
import org.jupnp.support.contentdirectory.callback.Browse
import org.jupnp.support.model.BrowseFlag
import org.jupnp.support.model.BrowseResult
import org.jupnp.support.model.DIDLContent
import org.jupnp.support.model.item.MusicTrack
import kotlin.coroutines.resume

data class DlnaServer(val udn: String, val name: String, val service: Service<*, *>)

data class DlnaEntry(
    val id: String,
    val title: String,
    /** フォルダなら true。false のとき [mediaItem] が再生対象。 */
    val isContainer: Boolean,
    val mediaItem: MediaItem? = null,
)

/** 1 回の Browse で取得する件数。 */
const val DLNA_PAGE_SIZE = 500L

/** Browse の 1 ページ分。 */
data class DlnaPage(
    val entries: List<DlnaEntry>,
    /** サーバーが返したオブジェクト数(フォルダ + 項目)。次ページの開始位置の計算に使う。再生できない項目も含む */
    val objectCount: Long,
    /** サーバーが答えた全件数。0 は不明 */
    val totalMatches: Long,
) {
    /**
     * この次のページがあるか。
     * 全件数(TotalMatches)は信用しきれない。実機の Music Server は、2 ページ目以降で全件数ではなく
     * 「開始位置以降の残り件数」を返す。そのため、全件数だけで打ち切ると最後のページが落ちる。
     * ページが要求した件数でいっぱいなら続きがあるとみなし、全件数が先の件数を示す場合も続ける。
     * (サーバーが 1 回の返却件数を絞り、かつ全件数が残り件数の場合だけは取りこぼす。)
     */
    fun hasMore(startIndex: Long, requested: Long): Boolean =
        objectCount > 0 &&
            (objectCount >= requested || (totalMatches > 0 && startIndex + objectCount < totalMatches))
}

class DlnaRepository(private val context: Context) {

    private val contentDirectory = UDAServiceType("ContentDirectory", 1)

    // 再生キューへ続きのページを足す処理用。画面(タブ)を離れても続けたいので画面とは独立させる。
    private val queueScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var queueJob: Job? = null

    private val _servers = MutableStateFlow<List<DlnaServer>>(emptyList())
    val servers: StateFlow<List<DlnaServer>> = _servers

    private var upnp: AndroidUpnpService? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val listener = object : DefaultRegistryListener() {
        override fun remoteDeviceAdded(registry: Registry, device: RemoteDevice) = refreshServers()
        override fun remoteDeviceRemoved(registry: Registry, device: RemoteDevice) = refreshServers()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = binder as AndroidUpnpService
            upnp = service
            service.registry.addListener(listener)
            refreshServers()
            service.controlPoint.search()
            Log.i(TAG, "UPnP service connected, search started")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            upnp = null
        }
    }

    fun start() {
        // マルチキャスト受信(SSDP)に必須。
        val wifi = context.getSystemService(WifiManager::class.java)
        multicastLock = wifi.createMulticastLock("myplayer-dlna").apply {
            setReferenceCounted(false)
            acquire()
        }
        context.bindService(
            Intent(context, MyUpnpService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    fun stop() {
        queueJob?.cancel()
        upnp?.registry?.removeListener(listener)
        upnp = null
        runCatching { context.unbindService(connection) }
        multicastLock?.takeIf { it.isHeld }?.release()
        multicastLock = null
    }

    /** 検索を再送する。権限を後から許可した場合などに使う。 */
    fun search() {
        upnp?.controlPoint?.search()
        refreshServers()
    }

    private fun refreshServers() {
        val registry = upnp?.registry ?: return
        _servers.value = registry.getDevices(contentDirectory)
            .mapNotNull { device -> device.toServer() }
            .sortedBy { it.name }
        Log.i(TAG, "servers=${_servers.value.map { it.name }}")
    }

    private fun Device<*, *, *>.toServer(): DlnaServer? {
        val service = findService(contentDirectory) ?: return null
        return DlnaServer(
            udn = identity.udn.identifierString,
            name = details?.friendlyName ?: identity.udn.identifierString,
            service = service,
        )
    }

    /**
     * [containerId] 直下の項目を [startIndex] から [count] 件取得する。ルートは "0"。
     * 次のページの開始位置は `startIndex + page.objectCount`。
     */
    suspend fun browse(
        server: DlnaServer,
        containerId: String,
        startIndex: Long = 0L,
        count: Long = DLNA_PAGE_SIZE,
    ): Result<DlnaPage> {
        val control = upnp?.controlPoint
            ?: return Result.failure(IllegalStateException("UPnP サービス未接続"))
        return suspendCancellableCoroutine { cont ->
            val callback = object : Browse(server.service, containerId, BrowseFlag.DIRECT_CHILDREN, "*", startIndex, count) {
                private var totalMatches = 0L

                // 全件数はここでしか取れない。true を返すと、続けて DIDL の解析と received が呼ばれる。
                override fun receivedRaw(invocation: ActionInvocation<*>, browseResult: BrowseResult): Boolean {
                    totalMatches = browseResult.totalMatchesLong
                    return true
                }

                override fun received(invocation: ActionInvocation<*>, didl: DIDLContent) {
                    val page = DlnaPage(
                        entries = didl.toEntries(),
                        objectCount = (didl.containers.size + didl.items.size).toLong(),
                        totalMatches = totalMatches,
                    )
                    Log.i(TAG, "browse $containerId start=$startIndex count=$count -> objects=${page.objectCount} total=${page.totalMatches}")
                    if (cont.isActive) cont.resume(Result.success(page))
                }

                override fun updateStatus(status: Status) = Unit

                override fun failure(invocation: ActionInvocation<*>, operation: UpnpResponse?, defaultMsg: String?) {
                    if (cont.isActive) {
                        cont.resume(Result.failure(RuntimeException(defaultMsg ?: "Browse に失敗")))
                    }
                }
            }
            val future = control.execute(callback)
            cont.invokeOnCancellation { future.cancel(true) }
        }
    }

    /**
     * 再生キューに、[startIndex] 以降のページを最後まで足していく。
     * フォルダ内の曲が複数ページにまたがっても、再生が最初のページで終わらないようにする。
     * 新しい再生を始めるときは [cancelQueueLoading] で止めること。
     */
    fun appendRemainingToQueue(
        server: DlnaServer,
        containerId: String,
        startIndex: Long,
        controller: MediaController,
    ) {
        queueJob?.cancel()
        queueJob = queueScope.launch {
            var start = startIndex
            while (true) {
                val page = browse(server, containerId, start).getOrNull() ?: break
                if (!controller.isConnected) break
                val items = page.entries.mapNotNull { it.mediaItem }
                if (items.isNotEmpty()) controller.addMediaItems(items)
                val more = page.hasMore(start, DLNA_PAGE_SIZE)
                start += page.objectCount
                if (!more) break
            }
        }
    }

    fun cancelQueueLoading() {
        queueJob?.cancel()
        queueJob = null
    }

    private fun DIDLContent.toEntries(): List<DlnaEntry> {
        val folders = containers.map { DlnaEntry(it.id, it.title.orEmpty(), isContainer = true) }
        val tracks = items.mapNotNull { item ->
            val res = item.resources.firstOrNull { it.protocolInfo?.contentFormat?.startsWith("audio/") == true }
                ?: item.firstResource
                ?: return@mapNotNull null
            val track = item as? MusicTrack
            val mediaItem = MediaItem.Builder()
                .setMediaId("dlna:${item.id}")
                .setUri(res.value)
                .setMimeType(res.protocolInfo?.contentFormat?.takeIf { it.startsWith("audio/") })
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.title)
                        .setArtist(track?.firstArtist?.name ?: item.creator)
                        .setAlbumTitle(track?.album)
                        .build(),
                )
                .build()
            DlnaEntry(item.id, item.title.orEmpty(), isContainer = false, mediaItem = mediaItem)
        }
        return folders + tracks
    }

    private companion object {
        const val TAG = "Dlna"
    }
}
