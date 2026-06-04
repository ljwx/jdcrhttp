package com.jdcr.jdcrhttp.response

data class SseEvent(
    val id: String? = null,
    val event: String? = null,
    val data: String = "",
    val retry: Long? = null,
)
