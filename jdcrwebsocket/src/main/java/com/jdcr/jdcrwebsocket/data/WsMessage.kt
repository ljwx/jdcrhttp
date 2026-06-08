package com.jdcr.jdcrwebsocket.data

sealed interface WsMessage {
    data class Text(val data: String) : WsMessage
    data class Binary(val data: ByteArray) : WsMessage
}