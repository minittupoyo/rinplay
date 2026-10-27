package net.minittu.rinplay.dlna

import android.util.Log
import org.jupnp.model.message.StreamRequestMessage
import org.jupnp.model.message.StreamResponseMessage
import org.jupnp.model.message.UpnpMessage
import org.jupnp.model.message.UpnpResponse
import org.jupnp.transport.spi.AbstractStreamClient
import org.jupnp.transport.spi.AbstractStreamClientConfiguration
import java.net.HttpURLConnection
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService

class HttpUrlStreamClientConfiguration(executor: ExecutorService) : AbstractStreamClientConfiguration(executor)

/**
 * Jetty を使わない jUPnP の HTTP クライアント。デバイス記述・SCPD の取得と SOAP(Browse)に使う。
 * HttpURLConnection は SUBSCRIBE/NOTIFY を送れないので、イベント購読は未対応。
 */
class HttpUrlStreamClient(
    private val config: HttpUrlStreamClientConfiguration,
) : AbstractStreamClient<HttpUrlStreamClientConfiguration, HttpURLConnection>() {

    override fun getConfiguration() = config

    override fun createRequest(message: StreamRequestMessage): HttpURLConnection {
        val connection = message.uri.toURL().openConnection() as HttpURLConnection
        connection.requestMethod = message.operation.httpMethodName
        connection.connectTimeout = config.timeoutSeconds * 1000
        connection.readTimeout = config.timeoutSeconds * 1000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty(
            "User-Agent",
            config.getUserAgentValue(message.udaMajorVersion, message.udaMinorVersion),
        )
        for ((name, values) in message.headers) {
            for (value in values) connection.addRequestProperty(name, value)
        }
        return connection
    }

    override fun createCallable(message: StreamRequestMessage, request: HttpURLConnection): Callable<StreamResponseMessage> =
        Callable {
            if (message.hasBody()) {
                val body = if (message.bodyType == UpnpMessage.BodyType.STRING) {
                    message.bodyString.toByteArray(message.contentTypeCharset ?: Charsets.UTF_8)
                } else {
                    message.bodyBytes
                }
                request.doOutput = true
                request.setFixedLengthStreamingMode(body.size)
                request.outputStream.use { it.write(body) }
            }

            val code = request.responseCode
            val response = StreamResponseMessage(UpnpResponse(code, request.responseMessage.orEmpty()))
            for ((name, values) in request.headerFields) {
                if (name == null) continue
                for (value in values) response.headers.add(name, value)
            }

            val stream = if (code >= 400) request.errorStream else request.inputStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (bytes.isNotEmpty()) {
                if (response.isContentTypeMissingOrText) {
                    response.setBodyCharacters(bytes)
                } else {
                    response.setBody(UpnpMessage.BodyType.BYTES, bytes)
                }
            }
            response
        }

    override fun abort(request: HttpURLConnection) {
        request.disconnect()
    }

    override fun logExecutionException(t: Throwable): Boolean {
        Log.w(TAG, "HTTP request failed", t)
        return false
    }

    override fun onFinally(request: HttpURLConnection) {
        request.disconnect()
    }

    override fun stop() = Unit

    private companion object {
        const val TAG = "Dlna"
    }
}
