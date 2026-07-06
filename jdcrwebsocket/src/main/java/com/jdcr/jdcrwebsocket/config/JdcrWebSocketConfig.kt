package com.jdcr.jdcrwebsocket.config

data class JdcrWSPingConfig(
    val pingInterval: Long = 3000L,
    /** 注意：Ktor 2.x 客户端 WebSockets 插件没有内置 pongTimeout，
     *  需要的话要自己做心跳超时检测，这里仅作占位 */
    val pongTimeout: Long = 15000L,
)

data class JdcrWebSocketConfig(
    val ping: JdcrWSPingConfig = JdcrWSPingConfig(),
    val connectTimeoutMs: Long = 15_000L,
    val maxFrameSize: Long = 6L * 1024 * 1024,
    val logEnable: Boolean = false,
)
