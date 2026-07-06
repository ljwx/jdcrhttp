package com.jdcr.jdcrwebsocket.data

import com.jdcr.jdcrhttp.response.JdcrHttpResult
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow

class JdcrWsConnection(
    val pathOrUrl: String,
    val events: Flow<JdcrHttpResult<WsEvent>>,
    private val session: DefaultClientWebSocketSession,
    private val readJob: Job,
    private val onClose: suspend (JdcrWsConnection) -> Unit,
) {

    suspend fun sendText(text: String) {
        session.send(Frame.Text(text))
    }

    suspend fun sendBinary(bytes: ByteArray) {
        session.send(Frame.Binary(true, bytes))
    }

    suspend fun close(reason: String = "手动关闭") {
        runCatching {
            session.close(CloseReason(CloseReason.Codes.NORMAL, reason))
        }
        onClose(this)
    }

}