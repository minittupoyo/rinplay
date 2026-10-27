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
import com.example.myplayer.dlna.MyUpnpService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

class DlnaRepository(private val context: Context) {

    private val contentDirectory = UDAServiceType("ContentDirectory", 1)

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

    /** [containerId] 直下の項目を取得する。ルートは "0"。 */
    suspend fun browse(server: DlnaServer, containerId: String): Result<List<DlnaEntry>> {
        val control = upnp?.controlPoint
            ?: return Result.failure(IllegalStateException("UPnP サービス未接続"))
        return suspendCancellableCoroutine { cont ->
            val callback = object : Browse(server.service, containerId, BrowseFlag.DIRECT_CHILDREN, "*", 0L, 500L) {
                override fun received(invocation: ActionInvocation<*>, didl: DIDLContent) {
                    if (cont.isActive) cont.resume(Result.success(didl.toEntries()))
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
