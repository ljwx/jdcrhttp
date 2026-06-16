package com.jdcr.jdcrwebsocket

import com.jdcr.jdcrhttp.IJdcrHttpManager
import com.jdcr.jdcrhttp.response.JdcrHttpResult
import com.jdcr.jdcrhttp.response.getRequestFailResult
import com.jdcr.jdcrhttp.util.JdcrHttpLog
import com.jdcr.jdcrwebsocket.client.JdcrWebSocketFactory
import com.jdcr.jdcrwebsocket.data.WsMessage
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readReason
import io.ktor.websocket.readText
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
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

class JdcrWebSocketManager(
    private val client: HttpClient,
    override val baseUrl: String
) : IJdcrHttpManager {

    companion object {
        @Volatile
        private var manager: JdcrWebSocketManager? = null
        fun initInstance(
            baseUrl: String,
            client: HttpClient? = null
        ): JdcrWebSocketManager {
            manager?.let { return it }
            return synchronized(this) {
                manager ?: JdcrWebSocketManager(
                    client ?: JdcrWebSocketFactory.getDefaultWebSocket(), baseUrl
                ).also { manager = it }
            }
        }

        fun instance(): JdcrWebSocketManager {
            return requireNotNull(manager) {
                "请先初始化,JdcrWebsocketManager.initInstance()"
            }
        }
    }

    private val sessions =
        ConcurrentHashMap<String, MutableSet<DefaultClientWebSocketSession>>()

    suspend fun connect(
        pathOrUrl: String,
        request: HttpRequestBuilder.() -> Unit = {},
        onMessage: (WsMessage) -> Unit,
        onReady: suspend DefaultClientWebSocketSession.() -> Unit,
    ): JdcrHttpResult<Any> {
        val url = resolveUrl(pathOrUrl)
        var session: DefaultClientWebSocketSession? = null
        try {
            session = client.webSocketSession(urlString = url, block = request)
            sessions.compute(url) { _, set ->
                (set ?: ConcurrentHashMap.newKeySet()).apply { add(session) }
            }
            try {
                coroutineScope {
                    launch {
                        for (frame in session.incoming) {
                            when (frame) {
                                is Frame.Ping -> JdcrHttpLog.d("🏓 收到 Ping")
                                is Frame.Pong -> JdcrHttpLog.d("🏓 收到 Pong")
                                is Frame.Close -> JdcrHttpLog.w("收到关闭帧:${frame.readReason()?.message ?: "未知原因"}")
                                is Frame.Text -> onMessage(WsMessage.Text(frame.readText()))
                                is Frame.Binary -> onMessage(WsMessage.Binary(frame.readBytes()))
                            }
                        }
                    }
                    launch {
                        session.onReady()
                    }
                }

            } finally {
                sessions.compute(url) { _, set ->
                    set?.apply { remove(session) }?.takeIf { it.isNotEmpty() }
                }
                runCatching { session.close() }
            }
            return JdcrHttpResult.Success(Unit)
        } catch (e: Exception) {
            session?.close()
            return getRequestFailResult(pathOrUrl, e)
        }
    }

    suspend fun disconnect(pathOrUrl: String) {
        val url = resolveUrl(pathOrUrl)
        sessions.remove(url)
            ?.forEach {
                runCatching {
                    it.close(
                        CloseReason(
                            CloseReason.Codes.NORMAL,
                            "手动断开连接"
                        )
                    )
                }
            }
    }

    fun disconnectAll() {
        sessions.values.forEach { set -> set.forEach { runCatching { it.cancel() } } }
        sessions.clear()
    }

    override fun destroyClient() {
        disconnectAll()
        client.close()
    }

}