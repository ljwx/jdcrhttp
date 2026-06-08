package com.jdcr.jdcrhttp.response

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * 按 W3C SSE 规范解析:空行分隔事件,id 字段粘性保留,以 ":" 开头为注释行。
 */
fun ByteReadChannel.asSseEvents(): Flow<JdcrSSEEvent> = flow {
    var id: String? = null
    var event: String? = null
    var retry: Long? = null
    val data = StringBuilder()
    suspend fun flushEventResponse() {
        if (data.isEmpty() && event == null) return
        emit(
            JdcrSSEEvent(
                id = id,
                event = event,
                data = data.toString().removeSuffix("\n"),
                retry = retry,
            )
        )
        // 按规范:id 在事件间是粘性的,其余字段每个事件后重置
        event = null
        retry = null
        data.setLength(0)
    }
    while (!isClosedForRead) {
        val line = readUTF8Line(Int.MAX_VALUE) ?: break
        when {
            line.isEmpty() -> flushEventResponse()
            line.startsWith(":") -> Unit // 注释行
            else -> {
                val idx = line.indexOf(':')
                val field = if (idx >= 0) line.substring(0, idx) else line
                var value = if (idx >= 0) line.substring(idx + 1) else ""
                if (value.startsWith(" ")) value = value.substring(1)
                when (field) {
                    "id" -> id = value
                    "event" -> event = value
                    "data" -> data.append(value).append('\n')
                    "retry" -> retry = value.toLongOrNull()
                }
            }
        }
    }
    flushEventResponse()
}.flowOn(Dispatchers.IO)