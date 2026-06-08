package com.jdcr.jdcrwebsocket

import com.jdcr.jdcrhttp.IJdcrHttpManager
import com.jdcr.jdcrwebsocket.client.JdcrWebSocketFactory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.utils.io.core.Closeable
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

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

class JdcrWebsocketManager(
    private val client: HttpClient,
    override val baseUrl: String
) : Closeable, IJdcrHttpManager {

    companion object {
        @Volatile
        private var manager: JdcrWebsocketManager? = null
        fun initInstance(
            baseUrl: String,
            client: HttpClient? = null
        ): JdcrWebsocketManager {
            manager?.let { return it }
            return synchronized(this) {
                manager ?: JdcrWebsocketManager(
                    client ?: JdcrWebSocketFactory.getDefaultWebSocket(), baseUrl
                ).also { manager = it }
            }
        }

        fun instance(): JdcrWebsocketManager {
            return requireNotNull(manager) {
                "请先初始化,JdcrWebsocketManager.initInstance()"
            }
        }
    }

    private val sessions =
        ConcurrentHashMap<String, MutableSet<DefaultClientWebSocketSession>>()

    suspend fun webSocket(
        pathOrUrl: String,
        request: HttpRequestBuilder.() -> Unit = {},
        handler: suspend DefaultClientWebSocketSession.() -> Unit,
    ) {
        val url = resolveUrl(pathOrUrl)
        client.webSocket(url, request) {
            val session = this
            sessions.compute(url) { _, set ->
                (set ?: ConcurrentHashMap.newKeySet()).apply { add(session) }
            }
            try {
                handler()
            } finally {
                sessions.compute(url) { _, set ->
                    set?.apply { remove(session) }?.takeIf { it.isNotEmpty() }
                }
            }
        }
    }

    fun disconnect(pathOrUrl: String) {
        val url = resolveUrl(pathOrUrl)
        sessions.remove(url)?.forEach { runCatching { it.cancel() } }
    }

    override fun close() {
        sessions.values.forEach { set -> set.forEach { runCatching { it.cancel() } } }
        sessions.clear()
        client.close()
    }

}