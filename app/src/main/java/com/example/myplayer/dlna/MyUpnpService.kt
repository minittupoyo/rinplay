package com.example.myplayer.dlna

import org.jupnp.UpnpServiceConfiguration
import org.jupnp.android.AndroidUpnpServiceConfiguration
import org.jupnp.android.AndroidUpnpServiceImpl
import org.jupnp.transport.spi.NetworkAddressFactory
import org.jupnp.transport.spi.StreamClient
import org.jupnp.transport.spi.StreamServer
import java.util.concurrent.Executors

/**
 * 標準の Android 設定は HTTP クライアント/サーバーに Jetty を使うが、Android 上では解決できず落ちる。
 * クライアントを HttpURLConnection 版に差し替え、サーバー(ローカルデバイス公開)は作らない。
 */
class MyUpnpConfiguration : AndroidUpnpServiceConfiguration() {
    override fun createStreamClient(): StreamClient<*> =
        HttpUrlStreamClient(HttpUrlStreamClientConfiguration(Executors.newCachedThreadPool()))

    override fun createStreamServer(networkAddressFactory: NetworkAddressFactory): StreamServer<*>? = null
}

class MyUpnpService : AndroidUpnpServiceImpl() {
    override fun createConfiguration(): UpnpServiceConfiguration = MyUpnpConfiguration()
}
