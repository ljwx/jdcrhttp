package com.jdcr.jdcrhttp.response

import com.jdcr.jdcrhttp.util.JdcrHttpLog
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.charsets.TooLongLineException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.SerializationException
import kotlin.coroutines.cancellation.CancellationException

const val DEFAULT_SSE_MAX_LINE_CHARS = 64 * 1024
const val DEFAULT_SSE_MAX_EVENT_CHARS = 1024 * 1024

class JdcrSseTooLargeException(
    message: String,
    cause: Throwable? = null,
) : SerializationException(message, cause)

@PublishedApi
internal suspend fun ByteReadChannel.readSseLine(
    maxLineChars: Int = DEFAULT_SSE_MAX_LINE_CHARS,
): String? {
    require(maxLineChars > 0)

    return try {
        readUTF8Line(maxLineChars)
    } catch (e: TooLongLineException) {
        throw JdcrSseTooLargeException("SSE 单行超过限制：$maxLineChars chars", e)
    }
}

/**
 * 按 W3C SSE 规范解析:空行分隔事件,id 字段粘性保留,以 ":" 开头为注释行。
 */
class JdcrSseLineParser(private val maxEventChars: Int = DEFAULT_SSE_MAX_EVENT_CHARS) {
    private var id: String? = null
    private var event: String? = null
    private var retry: Long? = null
    private val data = StringBuilder()

    init {
        require(maxEventChars > 0)
    }

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
            "data" -> {
                val nextLength =
                    data.length.toLong() + value.length.toLong() + 1L

                if (nextLength > maxEventChars.toLong()) {
                    throw JdcrSseTooLargeException(
                        "SSE 单事件超过限制：$maxEventChars chars"
                    )
                }

                data.append(value).append('\n')
            }

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
        val line = readSseLine() ?: break
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