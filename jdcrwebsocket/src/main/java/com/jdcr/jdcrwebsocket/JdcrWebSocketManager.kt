package com.jdcr.jdcrwebsocket

import com.jdcr.jdcrhttp.IJdcrHttpManager
import com.jdcr.jdcrhttp.response.JdcrHttpResult
import com.jdcr.jdcrhttp.response.getRequestFailResult
import com.jdcr.jdcrhttp.response.handleRequestResult
import com.jdcr.jdcrhttp.util.JdcrHttpLog
import com.jdcr.jdcrwebsocket.client.JdcrWebSocketFactory
import com.jdcr.jdcrwebsocket.data.JdcrWsConnection
import com.jdcr.jdcrwebsocket.data.WsEvent
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

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
    override val baseUrl: String,
    private val client: HttpClient
) : IJdcrHttpManager {

    companion object {
        @Volatile
        private var manager: JdcrWebSocketManager? = null
        fun initInstance(
            baseUrl: String,
            client: HttpClient? = null
        ): JdcrWebSocketManager {
            check(manager == null) {
                "JdcrWebSocketManager已初始化, 如需切换请先 destroyClient"
            }
            manager?.let { existing ->
                return existing
            }
            return manager ?: synchronized(this) {
                manager ?: JdcrWebSocketManager(
                    baseUrl, client ?: JdcrWebSocketFactory.getDefaultWebSocket()
                ).also {
                    JdcrHttpLog.i("JdcrWebSocketManager初始化成功")
                    manager = it
                }
            }
        }

        fun isInitialized(): Boolean {
            return manager != null
        }

        fun instance(): JdcrWebSocketManager {
            return requireNotNull(manager) {
                "请先初始化,JdcrWebsocketManager.initInstance()"
            }
        }
    }

    @PublishedApi
    internal val wsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val sessions =
        ConcurrentHashMap<String, MutableSet<DefaultClientWebSocketSession>>()

    private val connections = ConcurrentHashMap<String, MutableSet<JdcrWsConnection>>()
    private val connectionLock = Any()

    @Deprecated("Use connectConnection instead")
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
                                is Frame.Ping -> JdcrHttpLog.i("🏓 收到 Ping")
                                is Frame.Pong -> JdcrHttpLog.i("🏓 收到 Pong")
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

    private suspend fun addConnection(
        url: String,
        connection: JdcrWsConnection,
    ) {
        synchronized(connectionLock) {
            connections.compute(url) { _, set ->
                (set ?: ConcurrentHashMap.newKeySet()).apply {
                    add(connection)
                }
            }
        }
    }

    private suspend fun removeConnection(
        url: String,
        connection: JdcrWsConnection,
    ) {
        synchronized(connectionLock) {
            connections.compute(url) { _, set ->
                set?.apply {
                    remove(connection)
                }?.takeUnless {
                    it.isEmpty()
                }
            }
        }
    }

    suspend fun connectConnection(
        pathOrUrl: String,
        request: HttpRequestBuilder.() -> Unit = {},
    ): JdcrHttpResult<JdcrWsConnection> = handleRequestResult(pathOrUrl) {

        val url = resolveUrl(pathOrUrl)
        val session = client.webSocketSession(urlString = url, block = request)
        val events = Channel<JdcrHttpResult<WsEvent>>(Channel.BUFFERED)
        var connection: JdcrWsConnection? = null
        val readJob = wsScope.launch(start = CoroutineStart.LAZY) {
            try {
                events.send(JdcrHttpResult.Success(WsEvent.Open))

                for (frame in session.incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            events.send(JdcrHttpResult.Success(WsEvent.Text(frame.readText())))
                        }

                        is Frame.Binary -> {
                            events.send(JdcrHttpResult.Success(WsEvent.Binary(frame.readBytes())))
                        }

                        is Frame.Close -> {
                            val reason = frame.readReason()?.message ?: "未知原因"
                            JdcrHttpLog.w("收到关闭帧:$reason")
                            events.send(JdcrHttpResult.Success(WsEvent.Closing(reason)))
                            break
                        }

                        is Frame.Ping -> JdcrHttpLog.i("🏓 收到 Ping")
                        is Frame.Pong -> JdcrHttpLog.i("🏓 收到 Pong")
                    }
                }

                val closeReason = session.closeReason.await()?.message
                events.send(JdcrHttpResult.Success(WsEvent.Closed(closeReason)))
                events.close()
            } catch (e: CancellationException) {
                events.close(e)
                return@launch
            } catch (e: Exception) {
                events.trySend(getRequestFailResult(pathOrUrl, e))
            } finally {
                events.close()
                runCatching { session.close() }
                connection?.let {
                    removeConnection(url, it)
                }
            }
        }

        connection = JdcrWsConnection(
            pathOrUrl = pathOrUrl,
            events = events.receiveAsFlow()
                .onCompletion {
                    readJob.cancel()
                },
            session = session,
            readJob = readJob,
            onClose = { conn ->
                removeConnection(url, conn)
            },
        )
        addConnection(url, connection)
        readJob.start()
        connection
    }

    suspend fun disconnect(pathOrUrl: String) {
        val url = resolveUrl(pathOrUrl)
        val snapshot = synchronized(connectionLock) {
            connections.remove(url)?.toList().orEmpty()
        }
        snapshot.forEach { connection ->
            runCatching {
                connection.close("手动断开连接")
            }
        }
        //@Deprecated
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

    suspend fun disconnectAllAwait() {
        val snapshot = synchronized(connectionLock) {
            connections.values.flatMap { it.toList() }
                .also {
                    connections.clear()
                }
        }
        snapshot.forEach { connection ->
            runCatching {
                connection.close("手动断开全部连接")
            }
        }
        //@Deprecated
        sessions.values.forEach { set -> set.forEach { runCatching { it.cancel() } } }
        sessions.clear()
    }

    fun disconnectAll() {
        wsScope.launch {
            disconnectAllAwait()
        }
    }

    override fun destroyClient() {
        JdcrHttpLog.w("触发ws,destroyClient")
        synchronized(connectionLock) {
            connections.clear()
            sessions.clear()
        }
        wsScope.cancel()
        client.close()
        manager = null
    }

}