package com.jdcr.jdcrwebsocket.data

import com.jdcr.jdcrhttp.serialization.JdcrJsonCodec
import io.ktor.websocket.CloseReason

sealed interface WsMessage {
    data class Text(val data: String) : WsMessage
    data class Binary(val data: ByteArray) : WsMessage
}

sealed interface WsEvent {
    object Open : WsEvent
    data class Text(val data: String) : WsEvent {
        inline fun <reified T> toObject(): Result<T> = JdcrJsonCodec.fromJson<T>(data)

        fun getSimpleInfo(startIndex: Int = 0, endIndex: Int = 250): String =
            data.substring(startIndex.coerceAtLeast(0), endIndex.coerceAtMost(data.length))

    }

    data class Binary(val data: ByteArray) : WsEvent
    data class Closed(val reason: String?, val code: Short? = null) : WsEvent {

        /**
         * true 表示 RFC 6455 正常关闭码 1000。
         */
        val isNormal: Boolean
            get() = code == CloseReason.Codes.NORMAL.code
    }
}