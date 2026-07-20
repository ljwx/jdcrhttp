package com.jdcr.jdcrhttp.response

import com.jdcr.jdcrhttp.util.JdcrHttpLog
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.coroutines.cancellation.CancellationException

/**
 * 按 W3C SSE 规范解析:空行分隔事件,id 字段粘性保留,以 ":" 开头为注释行。
 */
class JdcrSseLineParser {
    private var id: String? = null
    private var event: String? = null
    private var retry: Long? = null
    private val data = StringBuilder()

    fun accept(line: String): JdcrSSEEvent? {
        if (line.isEmpty()) {
            if (data.isEmpty() && event == null) return null

            val sseEvent = JdcrSSEEvent(
                id = id,
                event = event,
                data = data.toString().removeSuffix("\n"),
                retry = retry,
            )

            event = null
            retry = null
            data.setLength(0)

            return sseEvent
        }

        if (line.startsWith(":")) return null

        val index = line.indexOf(':')
        val field = if (index >= 0) line.substring(0, index) else line
        var value = if (index >= 0) line.substring(index + 1) else ""
        if (value.startsWith(" ")) value = value.substring(1)

        when (field) {
            "id" -> id = value
            "event" -> event = value
            "data" -> data.append(value).append('\n')
            "retry" -> retry = value.toLongOrNull()
        }

        return null
    }

    fun finish(): JdcrSSEEvent? = flush()

    private fun flush(): JdcrSSEEvent? {
        if (data.isEmpty() && event == null) return null

        val sseEvent = JdcrSSEEvent(
            id = id,
            event = event,
            data = data.toString().removeSuffix("\n"),
            retry = retry,
        )

        event = null
        retry = null
        data.setLength(0)

        return sseEvent
    }

}

internal fun ByteReadChannel.asSseEventsResult(
    pathOrUrl: String
): Flow<JdcrHttpResult<JdcrSSEEvent>> = flow<JdcrHttpResult<JdcrSSEEvent>> {
    val parser = JdcrSseLineParser()
    while (!isClosedForRead) {
        val line = readUTF8Line(Int.MAX_VALUE / 20) ?: break
        JdcrHttpLog.v("SSE读到行: [${if (line.length > 150) line.substring(0, 140) else line}]")
        parser.accept(line)?.also { emit(JdcrHttpResult.Success(it)) }
    }
    parser.finish()?.also { emit(JdcrHttpResult.Success(it)) }
}.catch { e ->
    if (e is CancellationException) throw e
    val exception = e as? Exception ?: Exception(e)
    if (pathOrUrl.isNotEmpty()) {
        emit(getRequestFailResult(pathOrUrl, exception))
    } else {
        emit(JdcrHttpResult.Failure.LocalError.Network(exception))
    }
}.flowOn(Dispatchers.IO)