package com.jdcr.jdcrhttp.download

import com.jdcr.jdcrhttp.response.JdcrHttpResult
import java.io.File

sealed interface JdcrDownloadState {

    val destination: File

    data class Preparing(
        override val destination: File,
    ) : JdcrDownloadState

    data class Started(
        override val destination: File,
        val bytesDownloaded: Long,
        val totalBytes: Long?,
        val resumed: Boolean,
    ) : JdcrDownloadState

    data class Progress(
        override val destination: File,
        val bytesDownloaded: Long,
        val totalBytes: Long?,
        val bytesPerSecond: Long,
    ) : JdcrDownloadState {
        val fraction: Float?
            get() = totalBytes
                ?.takeIf { it > 0L }
                ?.let { (bytesDownloaded.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
    }

    data class Completed(
        override val destination: File,
        val bytesDownloaded: Long,
        val sha256: String?,
        val fromExistingFile: Boolean,
    ) : JdcrDownloadState

    data class Failed(
        override val destination: File,
        val error: JdcrHttpResult.Failure,
        val partialFile: File?,
        val canResume: Boolean,
    ) : JdcrDownloadState
}
