package com.jdcr.jdcrwebsocket

data class JdcrWSPingConfig(val pingInterval: Long = 3000L, val pongTimeout: Long = 15000L)

data class JdcrWebSocketConfig(val ping: JdcrWSPingConfig = JdcrWSPingConfig())