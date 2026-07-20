package com.jdcr.jdcrwebsocket.data

import com.jdcr.jdcrhttp.response.JdcrHttpResult
import com.jdcr.jdcrhttp.response.getRequestFailResult
import com.jdcr.jdcrhttp.serialization.JdcrJsonCodec
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max

class JdcrWsConnection(
    val pathOrUrl: String,
    val events: Flow<JdcrHttpResult<WsEvent>>,
    private val session: DefaultClientWebSocketSession,
    private val readJob: Job,
    private val onClose: suspend (JdcrWsConnection) -> Unit,
) {

    suspend fun sendText(text: String): JdcrHttpResult<Unit> {
        return try {
            session.send(Frame.Text(text))
            JdcrHttpResult.Success(Unit)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            JdcrHttpResult.Failure.LocalError.WsClosed(e)
        } catch (e: Exception) {
            getRequestFailResult(pathOrUrl, e)
        }
    }

    suspend inline fun <reified T> sendTextData(data: T): JdcrHttpResult<Unit> {
        val text = JdcrJsonCodec.toJson(data).getOrNull() ?: return getRequestFailResult(
            pathOrUrl,
            IllegalArgumentException("序列化失败")
        )
        return sendText(text)
    }

    suspend fun sendBinary(bytes: ByteArray): JdcrHttpResult<Unit> {
        return try {
            session.send(Frame.Binary(true, bytes))
            JdcrHttpResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            getRequestFailResult(pathOrUrl, e)
        }
    }

    fun configureHeartbeat(
        pingIntervalMillis: Long,
        pongTimeoutMillis: Long =
            if (pingIntervalMillis > 0) pingIntervalMillis * 2 else 15_000L
    ) {
        require(pingIntervalMillis == -1L || pingIntervalMillis > 0L) {
            "pingIntervalMillis必须为-1或正数"
        }
        if (pingIntervalMillis > 0) {
            require(pongTimeoutMillis > 0L) {
                "pongTimeoutMillis必须为正数"
            }
            session.timeoutMillis = pongTimeoutMillis
        }
        session.pingIntervalMillis = pingIntervalMillis
    }

    suspend fun close(reason: String = "手动关闭", timeout: Long = 3_000) {
        runCatching {
            session.close(CloseReason(CloseReason.Codes.NORMAL, reason))
        }
        onClose(this)
        withTimeoutOrNull(max(1000, timeout)) {
            readJob.join()
        }
        if (readJob.isActive) {
            readJob.cancel()
        }
    }

}