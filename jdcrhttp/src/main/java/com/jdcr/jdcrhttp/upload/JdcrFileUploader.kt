package com.jdcr.jdcrhttp.upload

import com.jdcr.jdcrhttp.request.JdcrRequestOptions
import com.jdcr.jdcrhttp.request.applyJdcrRequest
import com.jdcr.jdcrhttp.response.JdcrHttpResult
import com.jdcr.jdcrhttp.response.failureOrNull
import com.jdcr.jdcrhttp.response.getRequestFailResult
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.util.cio.readChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

internal class JdcrFileUploader(
    private val clientProvider: () -> HttpClient,
    private val resolveUrl: (String) -> String,
) {

    fun <T> upload(
        request: JdcrUploadRequest,
        options: JdcrRequestOptions,
        parseResponse: suspend (HttpResponse) -> T,
    ): Flow<JdcrUploadState<T>> = flow {
        emit(JdcrUploadState.Preparing(request.files))

        val bytesUploaded = AtomicLong(0L)
        try {
            validateFiles(request)
            uploadLocked(request, options, bytesUploaded, parseResponse)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val failure = getRequestFailResult<Unit>(request.pathOrUrl, error)
                .failureOrNull()
                ?: JdcrHttpResult.Failure.LocalError.Unknown(error)
            emit(
                JdcrUploadState.Failed(
                    error = failure,
                    bytesUploaded = bytesUploaded.get(),
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun <T> FlowCollector<JdcrUploadState<T>>.uploadLocked(
        request: JdcrUploadRequest,
        options: JdcrRequestOptions,
        bytesUploaded: AtomicLong,
        parseResponse: suspend (HttpResponse) -> T,
    ) {
        val progressEvents = Channel<ProgressSnapshot>(Channel.BUFFERED)
        val transferStartedAt = System.nanoTime()
        val rateLimiter = request.maxBytesPerSecond?.let {
            UploadRateLimiter(it, transferStartedAt)
        }

        var totalBytes: Long? = null
        val content = buildOutgoingContent(request) { chunkSize ->
            rateLimiter?.acquire(chunkSize)
            val uploaded = bytesUploaded.addAndGet(chunkSize.toLong())
            progressEvents.send(
                ProgressSnapshot(
                    bytesUploaded = uploaded,
                    totalBytes = totalBytes,
                    startedAt = transferStartedAt,
                    now = System.nanoTime(),
                )
            )
        }
        totalBytes = content.contentLength

        emit(
            JdcrUploadState.Started(
                bytesUploaded = 0L,
                totalBytes = totalBytes,
            )
        )

        coroutineScope {
            val uploadJob = async {
                try {
                    val response = clientProvider().request {
                        method = request.method.toHttpMethod()
                        url(resolveUrl(request.pathOrUrl))
                        timeout {
                            requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                        }
                        applyJdcrRequest(options)
                        headers.remove(HttpHeaders.ContentType)
                        setBody(content)
                    }
                    UploadOutcome(
                        bytesUploaded = bytesUploaded.get(),
                        statusCode = response.status.value,
                        data = parseResponse(response),
                        totalBytes = totalBytes,
                    )
                } finally {
                    progressEvents.close()
                }
            }

            var lastProgressAt = 0L
            var lastProgressBytes = -1L

            for (snapshot in progressEvents) {
                val intervalElapsed = snapshot.now - lastProgressAt >=
                    request.progressIntervalMillis * NANOS_PER_MILLISECOND
                val finished = snapshot.totalBytes != null &&
                    snapshot.bytesUploaded == snapshot.totalBytes
                if (intervalElapsed || finished || lastProgressBytes < 0L) {
                    emit(
                        JdcrUploadState.Progress(
                            bytesUploaded = snapshot.bytesUploaded,
                            totalBytes = snapshot.totalBytes,
                            bytesPerSecond = snapshot.bytesPerSecond(),
                        )
                    )
                    lastProgressAt = snapshot.now
                    lastProgressBytes = snapshot.bytesUploaded
                }
            }

            val outcome = uploadJob.await()
            if (outcome.bytesUploaded != lastProgressBytes) {
                emit(
                    JdcrUploadState.Progress(
                        bytesUploaded = outcome.bytesUploaded,
                        totalBytes = outcome.totalBytes,
                        bytesPerSecond = ProgressSnapshot(
                            bytesUploaded = outcome.bytesUploaded,
                            totalBytes = outcome.totalBytes,
                            startedAt = transferStartedAt,
                            now = System.nanoTime(),
                        ).bytesPerSecond(),
                    )
                )
            }

            emit(
                JdcrUploadState.Completed(
                    bytesUploaded = outcome.bytesUploaded,
                    statusCode = outcome.statusCode,
                    data = outcome.data,
                )
            )
        }
    }

    private fun validateFiles(request: JdcrUploadRequest) {
        request.files.forEach { file ->
            if (!file.exists()) {
                throw JdcrUploadProtocolException("上传文件不存在: ${file.absolutePath}")
            }
            if (!file.isFile) {
                throw JdcrUploadProtocolException("上传路径不是普通文件: ${file.absolutePath}")
            }
            if (!file.canRead()) {
                throw JdcrUploadProtocolException("上传文件不可读: ${file.absolutePath}")
            }
        }
    }

    private fun buildOutgoingContent(
        request: JdcrUploadRequest,
        onBytesWritten: suspend (Int) -> Unit,
    ): OutgoingContent.WriteChannelContent {
        return when (val body = request.body) {
            is JdcrUploadBody.Binary -> BinaryFileOutgoingContent(
                file = body.file,
                contentType = ContentType.parse(body.contentType),
                fileName = body.fileName,
                bufferSize = request.bufferSize,
                onBytesWritten = onBytesWritten,
            )

            is JdcrUploadBody.Multipart -> {
                val parts = formData {
                    body.fields.forEach { (name, value) ->
                        append(name, value)
                    }
                    body.files.forEach { part ->
                        val type = resolveContentType(part)
                        append(
                            key = part.fieldName,
                            value = ChannelProvider(size = part.file.length()) {
                                part.file.readChannel()
                            },
                            headers = Headers.build {
                                append(
                                    HttpHeaders.ContentDisposition,
                                    "filename=\"${escapeFileName(part.fileName)}\""
                                )
                                append(HttpHeaders.ContentType, type.toString())
                            },
                        )
                    }
                }
                ProgressTrackingContent(
                    delegate = MultiPartFormDataContent(parts),
                    bufferSize = request.bufferSize,
                    onBytesWritten = onBytesWritten,
                )
            }
        }
    }

    private data class ProgressSnapshot(
        val bytesUploaded: Long,
        val totalBytes: Long?,
        val startedAt: Long,
        val now: Long,
    ) {
        fun bytesPerSecond(): Long {
            val elapsedNanos = (now - startedAt).coerceAtLeast(1L)
            return (bytesUploaded.toDouble() * NANOS_PER_SECOND / elapsedNanos).toLong()
        }
    }

    private data class UploadOutcome<T>(
        val bytesUploaded: Long,
        val statusCode: Int,
        val data: T,
        val totalBytes: Long?,
    )

    companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val NANOS_PER_SECOND = 1_000_000_000L
    }
}

internal fun JdcrUploadMethod.toHttpMethod(): HttpMethod = when (this) {
    JdcrUploadMethod.POST -> HttpMethod.Post
    JdcrUploadMethod.PUT -> HttpMethod.Put
    JdcrUploadMethod.PATCH -> HttpMethod.Patch
}

/**
 * 以本次上传的平均速度限流。等待发生在写出前，取消时不会把未写出的块计入进度。
 */
private class UploadRateLimiter(
    private val maxBytesPerSecond: Long,
    private val startedAtNanos: Long,
) {
    private var acquiredBytes = 0L

    suspend fun acquire(byteCount: Int) {
        acquiredBytes += byteCount
        val expectedElapsedNanos =
            acquiredBytes.toDouble() * NANOS_PER_SECOND / maxBytesPerSecond
        val actualElapsedNanos = (System.nanoTime() - startedAtNanos).coerceAtLeast(0L)
        val remainingNanos = expectedElapsedNanos - actualElapsedNanos
        if (remainingNanos > 0.0) {
            val delayMillis = kotlin.math.ceil(remainingNanos / NANOS_PER_MILLISECOND)
                .toLong()
                .coerceAtLeast(1L)
            delay(delayMillis)
        }
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000.0
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
