package com.jdcr.jdcrwebsocket

import com.jdcr.jdcrhttp.IJdcrHttpManager
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.utils.io.core.Closeable
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class JdcrWebsocketManager(val client: HttpClient, private val baseUrl: String = "") : Closeable,
    IJdcrHttpManager {

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

    }

}