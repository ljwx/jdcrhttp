package com.jdcr.jdcrhttp.response

data class JdcrSSEEvent(
    val id: String? = null,
    val event: String? = null,
    val data: String = "",
    val retry: Long? = null,
)
