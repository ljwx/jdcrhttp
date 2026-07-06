package com.jdcr.jdcrhttp.response

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow

data class JdcrSSEEvent(
    val id: String? = null,
    val event: String? = null,
    val data: String = "",
    val retry: Long? = null,
)

class JdcrSseConnection(
    val pathOrUrl: String,
    val events: Flow<JdcrHttpResult<JdcrSSEEvent>>,
    private val readJob: Job,
    private val onClose: suspend (JdcrSseConnection) -> Unit,
) {
    suspend fun close() {
        readJob.cancel()
        onClose(this)
    }

}
