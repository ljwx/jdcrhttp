package com.jdcr.jdcrwebsocket

import com.jdcr.jdcrhttp.JdcrHttpClientFactory.getDefaultHttp
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets

object JdcrWebSocketFactory {

    fun getDefaultWebsocket(config: JdcrWebSocketConfig): HttpClient {
        return getDefaultHttp {
            install(WebSockets) {
                pingInterval = 10_000 // 10秒发送一次 Ping（单位：毫秒）
            }
        }
    }

}