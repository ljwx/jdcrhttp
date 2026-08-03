package com.jdcr.jdcrhttp.upload

import com.jdcr.jdcrhttp.response.JdcrHttpResult
import java.io.File

sealed interface JdcrUploadState<out T> {

    data class Preparing(
        val files: List<File>,
    ) : JdcrUploadState<Nothing>

    data class Started(
        val bytesUploaded: Long,
        val totalBytes: Long?,
    ) : JdcrUploadState<Nothing>

    data class Progress(
        val bytesUploaded: Long,
        val totalBytes: Long?,
        val bytesPerSecond: Long,
    ) : JdcrUploadState<Nothing> {
        val fraction: Float?
            get() = totalBytes
                ?.takeIf { it > 0L }
                ?.let { (bytesUploaded.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
    }

    data class Completed<T>(
        val bytesUploaded: Long,
        val statusCode: Int,
        val data: T,
    ) : JdcrUploadState<T>

    data class Failed(
        val error: JdcrHttpResult.Failure,
        val bytesUploaded: Long,
    ) : JdcrUploadState<Nothing>
}
