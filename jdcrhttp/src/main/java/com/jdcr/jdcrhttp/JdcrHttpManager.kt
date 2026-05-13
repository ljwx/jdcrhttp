package com.jdcr.jdcrhttp

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.core.Closeable
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class JdcrHttpManager private constructor(
    val client: HttpClient,
    private val baseUrl: String = "",
) : Closeable {

    companion object {
        private var customClient: HttpClient? = null
        private val client by lazy { customClient ?: JdcrHttpClientFactory.getDefaultHttp() }
        private var baseUrl: String = ""
        private val manager by lazy { JdcrHttpManager(client, baseUrl) }

        fun init(baseUrl: String) {
            this.baseUrl = baseUrl
        }

        fun init(baseUrl: String, client: HttpClient) {
            this.baseUrl = baseUrl
            this.customClient = client
        }

        fun instance(): JdcrHttpManager {
            return manager
        }
    }

    fun clearBearerTokenCache() {
        JdcrHttpClientFactory.clearBearerTokenCache()
    }

    fun resolveUrl(pathOrUrl: String): String {
        val trimmed = pathOrUrl.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        val base = baseUrl.trimEnd('/')
        if (base.isEmpty()) return trimmed.trimStart('/')
        val path = trimmed.trimStart('/')
        return "$base/$path"
    }

    suspend inline fun <reified T> get(
        pathOrUrl: String,
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ): T = client.get {
        url(resolveUrl(pathOrUrl))
        block()
    }.body()

    suspend inline fun <reified T> post(
        pathOrUrl: String,
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ): T = client.post {
        url(resolveUrl(pathOrUrl))
        block()
    }.body()

    suspend inline fun <reified T> put(
        pathOrUrl: String,
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ): T = client.put {
        url(resolveUrl(pathOrUrl))
        block()
    }.body()

    suspend inline fun <reified T> patch(
        pathOrUrl: String,
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ): T = client.patch {
        url(resolveUrl(pathOrUrl))
        block()
    }.body()

    suspend inline fun <reified T> delete(
        pathOrUrl: String,
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ): T = client.delete {
        url(resolveUrl(pathOrUrl))
        block()
    }.body()

    suspend fun getRaw(
        pathOrUrl: String,
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = client.get {
        url(resolveUrl(pathOrUrl))
        block()
    }

    suspend fun postRaw(
        pathOrUrl: String,
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = client.post {
        url(resolveUrl(pathOrUrl))
        block()
    }

    suspend fun putRaw(
        pathOrUrl: String,
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = client.put {
        url(resolveUrl(pathOrUrl))
        block()
    }

    suspend fun patchRaw(
        pathOrUrl: String,
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = client.patch {
        url(resolveUrl(pathOrUrl))
        block()
    }

    suspend fun deleteRaw(
        pathOrUrl: String,
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = client.delete {
        url(resolveUrl(pathOrUrl))
        block()
    }

    suspend fun getText(
        pathOrUrl: String,
        block: HttpRequestBuilder.() -> Unit = {},
    ): String = getRaw(pathOrUrl, block).bodyAsText()

    suspend fun webSocket(
        pathOrUrl: String,
        request: HttpRequestBuilder.() -> Unit = {},
        handler: suspend DefaultClientWebSocketSession.() -> Unit,
    ) {
        client.webSocket(resolveUrl(pathOrUrl), request, handler)
    }

    fun WebSocketSession.incomingText(): Flow<String> = flow {
        val channel: ReceiveChannel<Frame> = incoming
        for (frame in channel) {
            when (frame) {
                is Frame.Text -> emit(frame.readText())
                is Frame.Close -> break
                else -> Unit
            }
        }
    }

    override fun close() {
        client.close()
    }

}

suspend inline fun <T> JdcrHttpManager.runCatchingCancellable(crossinline block: suspend JdcrHttpManager.() -> T): Result<T> {
    return try {
        Result.success(block(this))
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Throwable) {
        Result.failure(e)
    }
}
