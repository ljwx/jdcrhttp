package com.jdcr.jdcrwebsocket.client

import com.jdcr.jdcrhttp.util.JdcrHttpLog
import com.jdcr.jdcrhttp.util.JdcrHttpUtils
import com.jdcr.jdcrwebsocket.config.JdcrWebSocketConfig
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.CIOEngineConfig
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.WebSockets

object JdcrWebSocketFactory {

    fun getDefaultWebSocket(
        config: JdcrWebSocketConfig = JdcrWebSocketConfig(),
        configureEngine: CIOEngineConfig.() -> Unit = {},
        configureClient: HttpClientConfig<*>.() -> Unit = {},
    ): HttpClient {
        return HttpClient(CIO) {
            engine {
                requestTimeout = 0 // 长连接：不限制整请求时长，避免 WS 被超时掐断
                endpoint {
                    connectTimeout = config.connectTimeoutMs
                }
                configureEngine() // 外层传入：在默认引擎参数之后再改 CIOEngineConfig
            }

            install(HttpTimeout)

            install(WebSockets) {
                pingInterval = config.ping.pingInterval // 用上配置，不再硬编码
                maxFrameSize = config.maxFrameSize
            }

            if (config.logEnable) {
                install(Logging) {
                    level = LogLevel.INFO
                    logger = object : Logger {
                        override fun log(message: String) {
                            JdcrHttpLog.i(
                                JdcrHttpUtils.sanitizeLogMessage(
                                    message,
                                    "****",
                                )
                            )
                        }
                    }
                }
            }

            configureClient() // 外层传入：在所有内置插件之后追加 Ktor 配置

        }
    }

}