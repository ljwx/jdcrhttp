package com.jdcr.jdcrhttp.test

data class TtsEventRequest(val eventCode: Int, val body: TtsEventRequestBody)

data class TtsEventRequestBody(
    val bizType: Int = 1,
    val userId: String = "656498080",
    val sessionId: String = "",
    val chatId: Int = 3,
    val content: String = "疯狂星期四",
)