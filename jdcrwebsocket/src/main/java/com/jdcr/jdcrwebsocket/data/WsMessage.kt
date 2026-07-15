package com.jdcr.jdcrwebsocket.data

import com.jdcr.jdcrhttp.serialization.JdcrJsonCodec

sealed interface WsMessage {
    data class Text(val data: String) : WsMessage
    data class Binary(val data: ByteArray) : WsMessage
}

sealed interface WsEvent {
    object Open : WsEvent
    data class Text(val data: String) : WsEvent {
        inline fun <reified T> toObject(): Result<T> = JdcrJsonCodec.fromJson<T>(data)
    }

    data class Binary(val data: ByteArray) : WsEvent
    data class Closing(val reason: String?) : WsEvent
    data class Closed(val reason: String?) : WsEvent
}