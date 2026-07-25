package com.jdcr.jdcrhttp.download

import android.system.ErrnoException
import android.system.Os
import com.jdcr.jdcrhttp.request.JdcrRequestOptions
import com.jdcr.jdcrhttp.request.applyJdcrRequest
import com.jdcr.jdcrhttp.response.JdcrHttpResult
import com.jdcr.jdcrhttp.response.failureOrNull
import com.jdcr.jdcrhttp.response.getRequestFailResult
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.RedirectResponseException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Properties
import kotlin.coroutines.cancellation.CancellationException

internal class JdcrFileDownloader(
    private val clientProvider: () -> HttpClient,
    private val resolveUrl: (String) -> String,
) {
    private val destinationLocks = mutableMapOf<String, Mutex>()
    private val destinationLocksGuard = Any()

    fun download(
        request: JdcrDownloadRequest,
        options: JdcrRequestOptions,
    ): Flow<JdcrDownloadState> = flow {
        emit(JdcrDownloadState.Preparing(request.destination))

        val lockKey = runCatching { request.destination.canonicalPath }
            .getOrElse { request.destination.absolutePath }
        val lock = synchronized(destinationLocksGuard) {
            destinationLocks.getOrPut(lockKey) { Mutex() }
        }

        try {
            lock.withLock {
                downloadLocked(request, options)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val failure = getRequestFailResult<Unit>(request.pathOrUrl, error)
                .failureOrNull()
                ?: JdcrHttpResult.Failure.LocalError.Unknown(error)
            val metadata = readMetadata(request.metadataFile)
            val resumable = request.partialFile.length() > 0L &&
                metadata?.sourceKey == sourceKey(resolveUrl(request.pathOrUrl)) &&
                metadata.validator.value.isNotBlank()

            emit(
                JdcrDownloadState.Failed(
                    destination = request.destination,
                    error = failure,
                    partialFile = request.partialFile.takeIf(File::exists),
                    canResume = resumable,
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun FlowCollector<JdcrDownloadState>.downloadLocked(
        request: JdcrDownloadRequest,
        options: JdcrRequestOptions,
    ) {
        prepareDestination(request)

        if (request.destination.exists() && !request.overwriteExisting) {
            val sha256 = if (request.calculateSha256 || request.expectedSha256 != null) {
                sha256(request.destination)
            } else {
                null
            }
            verifyChecksum(request, sha256)
            emit(
                JdcrDownloadState.Completed(
                    destination = request.destination,
                    bytesDownloaded = request.destination.length(),
                    sha256 = sha256,
                    fromExistingFile = true,
                )
            )
            return
        }

        val url = resolveUrl(request.pathOrUrl)
        var restartAttempted = false

        while (true) {
            val resumeContext = resolveResumeContext(request, url)
            val outcome = executeRequest(request, options, url, resumeContext)
            if (outcome == RequestOutcome.Completed) return

            if (restartAttempted) {
                throw JdcrDownloadProtocolException(
                    "服务端连续拒绝从 0 开始下载: ${request.pathOrUrl}"
                )
            }
            restartAttempted = true
            resetPartial(request)
        }
    }

    private suspend fun FlowCollector<JdcrDownloadState>.executeRequest(
        request: JdcrDownloadRequest,
        options: JdcrRequestOptions,
        url: String,
        resumeContext: ResumeContext,
    ): RequestOutcome {
        val statement = clientProvider().prepareGet {
            url(url)
            expectSuccess = false
            timeout {
                requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
            }
            applyJdcrRequest(options)

            headers.remove(HttpHeaders.AcceptEncoding)
            headers.append(HttpHeaders.AcceptEncoding, "identity")
            headers.remove(HttpHeaders.Range)
            headers.remove(HttpHeaders.IfRange)
            if (resumeContext.offset > 0L) {
                val metadata = requireNotNull(resumeContext.metadata) {
                    "续传请求缺少文件校验信息"
                }
                headers.append(HttpHeaders.Range, "bytes=${resumeContext.offset}-")
                headers.append(HttpHeaders.IfRange, metadata.validator.value)
            }
        }

        return statement.execute { response ->
            when (response.status) {
                HttpStatusCode.OK -> {
                    transferResponse(
                        request = request,
                        url = url,
                        response = response,
                        offset = 0L,
                        totalBytes = response.contentLength(),
                        resumed = false,
                        previousMetadata = null,
                    )
                    RequestOutcome.Completed
                }

                HttpStatusCode.PartialContent -> {
                    val contentRange = parseContentRange(
                        response.headers[HttpHeaders.ContentRange]
                    ) ?: throw JdcrDownloadProtocolException(
                        "206 响应缺少合法的 Content-Range"
                    )
                    if (contentRange.start != resumeContext.offset) {
                        throw JdcrDownloadProtocolException(
                            "Content-Range 起点错误，expected=${resumeContext.offset}, " +
                                "actual=${contentRange.start}"
                        )
                    }
                    val rangeLength = contentRange.endInclusive - contentRange.start + 1L
                    response.contentLength()?.let { contentLength ->
                        if (contentLength != rangeLength) {
                            throw JdcrDownloadProtocolException(
                                "Content-Length 与 Content-Range 不一致"
                            )
                        }
                    }

                    transferResponse(
                        request = request,
                        url = url,
                        response = response,
                        offset = resumeContext.offset,
                        totalBytes = contentRange.total,
                        resumed = resumeContext.offset > 0L,
                        previousMetadata = resumeContext.metadata,
                    )
                    RequestOutcome.Completed
                }

                HttpStatusCode.RequestedRangeNotSatisfiable -> {
                    val totalBytes = parseUnsatisfiedContentRange(
                        response.headers[HttpHeaders.ContentRange]
                    )
                    if (resumeContext.offset > 0L && totalBytes == resumeContext.offset) {
                        completeExistingPartial(request, totalBytes)
                        RequestOutcome.Completed
                    } else {
                        RequestOutcome.RestartFromZero
                    }
                }

                else -> throw responseException(response)
            }
        }
    }

    private suspend fun FlowCollector<JdcrDownloadState>.transferResponse(
        request: JdcrDownloadRequest,
        url: String,
        response: HttpResponse,
        offset: Long,
        totalBytes: Long?,
        resumed: Boolean,
        previousMetadata: DownloadMetadata?,
    ) {
        val responseValidator = response.downloadValidator()
        if (previousMetadata != null && responseValidator != null &&
            previousMetadata.validator != responseValidator
        ) {
            throw JdcrDownloadProtocolException("续传响应的文件校验标识已经变化")
        }
        if (totalBytes != null && totalBytes < offset) {
            throw JdcrDownloadProtocolException("响应文件总长度小于已下载长度")
        }

        val activeValidator = previousMetadata?.validator ?: responseValidator
        if (activeValidator == null) {
            request.metadataFile.delete()
        } else {
            writeMetadata(
                request.metadataFile,
                DownloadMetadata(sourceKey(url), activeValidator)
            )
        }

        emit(
            JdcrDownloadState.Started(
                destination = request.destination,
                bytesDownloaded = offset,
                totalBytes = totalBytes,
                resumed = resumed,
            )
        )

        val shouldHash = request.calculateSha256 || request.expectedSha256 != null
        val digest = MessageDigest.getInstance(SHA_256).takeIf { shouldHash }
        if (offset > 0L) {
            digest?.updateFrom(request.partialFile)
        }

        var bytesDownloaded = offset
        val transferStartedAt = System.nanoTime()
        var lastProgressAt = transferStartedAt
        var lastProgressBytes = offset
        val buffer = ByteArray(request.bufferSize)
        val body = response.bodyAsChannel()

        RandomAccessFile(request.partialFile, "rw").use { output ->
            if (offset == 0L) {
                output.setLength(0L)
            } else {
                output.seek(offset)
            }

            while (true) {
                val read = body.readAvailable(buffer)
                if (read == -1) break
                if (read == 0) continue

                output.write(buffer, 0, read)
                digest?.update(buffer, 0, read)
                bytesDownloaded += read

                val now = System.nanoTime()
                val intervalElapsed = now - lastProgressAt >=
                    request.progressIntervalMillis * NANOS_PER_MILLISECOND
                val downloadFinished = totalBytes != null && bytesDownloaded == totalBytes
                if (intervalElapsed || downloadFinished) {
                    emitProgress(
                        request = request,
                        bytesDownloaded = bytesDownloaded,
                        totalBytes = totalBytes,
                        bytesTransferred = bytesDownloaded - offset,
                        startedAt = transferStartedAt,
                        now = now,
                    )
                    lastProgressAt = now
                    lastProgressBytes = bytesDownloaded
                }
            }
            output.fd.sync()
        }

        if (bytesDownloaded != lastProgressBytes) {
            emitProgress(
                request = request,
                bytesDownloaded = bytesDownloaded,
                totalBytes = totalBytes,
                bytesTransferred = bytesDownloaded - offset,
                startedAt = transferStartedAt,
                now = System.nanoTime(),
            )
        }

        response.contentLength()?.let { responseLength ->
            if (bytesDownloaded - offset != responseLength) {
                throw JdcrDownloadProtocolException("响应体在 Content-Length 之前结束")
            }
        }
        if (totalBytes != null && bytesDownloaded != totalBytes) {
            throw JdcrDownloadProtocolException(
                "下载未完成，downloaded=$bytesDownloaded, total=$totalBytes"
            )
        }

        val sha256 = digest?.digest()?.toHexString()
        verifyChecksum(request, sha256)
        finishDownload(request, bytesDownloaded, sha256)
    }

    private suspend fun FlowCollector<JdcrDownloadState>.completeExistingPartial(
        request: JdcrDownloadRequest,
        totalBytes: Long,
    ) {
        emit(
            JdcrDownloadState.Started(
                destination = request.destination,
                bytesDownloaded = totalBytes,
                totalBytes = totalBytes,
                resumed = true,
            )
        )
        val sha256 = if (request.calculateSha256 || request.expectedSha256 != null) {
            sha256(request.partialFile)
        } else {
            null
        }
        verifyChecksum(request, sha256)
        finishDownload(request, totalBytes, sha256)
    }

    private suspend fun FlowCollector<JdcrDownloadState>.finishDownload(
        request: JdcrDownloadRequest,
        bytesDownloaded: Long,
        sha256: String?,
    ) {
        try {
            Os.rename(request.partialFile.absolutePath, request.destination.absolutePath)
        } catch (error: ErrnoException) {
            throw IOException("无法将临时文件原子移动到目标位置", error)
        }
        request.metadataFile.delete()
        emit(
            JdcrDownloadState.Completed(
                destination = request.destination,
                bytesDownloaded = bytesDownloaded,
                sha256 = sha256,
                fromExistingFile = false,
            )
        )
    }

    private suspend fun FlowCollector<JdcrDownloadState>.emitProgress(
        request: JdcrDownloadRequest,
        bytesDownloaded: Long,
        totalBytes: Long?,
        bytesTransferred: Long,
        startedAt: Long,
        now: Long,
    ) {
        val elapsedNanos = (now - startedAt).coerceAtLeast(1L)
        val bytesPerSecond = (bytesTransferred.toDouble() * NANOS_PER_SECOND / elapsedNanos)
            .toLong()
        emit(
            JdcrDownloadState.Progress(
                destination = request.destination,
                bytesDownloaded = bytesDownloaded,
                totalBytes = totalBytes,
                bytesPerSecond = bytesPerSecond,
            )
        )
    }

    private fun prepareDestination(request: JdcrDownloadRequest) {
        val destination = request.destination.absoluteFile
        require(!destination.isDirectory) { "destination 不能是目录" }

        val parent = requireNotNull(destination.parentFile) { "destination 必须有父目录" }
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("无法创建下载目录: ${parent.absolutePath}")
        }
        require(parent.isDirectory) { "下载目标的父路径不是目录" }

        if (!request.resume) {
            resetPartial(request)
        }
    }

    private fun resolveResumeContext(
        request: JdcrDownloadRequest,
        url: String,
    ): ResumeContext {
        if (!request.resume || !request.partialFile.isFile || request.partialFile.length() <= 0L) {
            request.metadataFile.delete()
            return ResumeContext(0L, null)
        }

        val metadata = readMetadata(request.metadataFile)
        val canResume = metadata != null &&
            metadata.sourceKey == sourceKey(url) &&
            metadata.validator.value.isNotBlank()
        if (!canResume) {
            resetPartial(request)
            return ResumeContext(0L, null)
        }
        return ResumeContext(request.partialFile.length(), metadata)
    }

    private fun resetPartial(request: JdcrDownloadRequest) {
        request.partialFile.delete()
        request.metadataFile.delete()
    }

    private fun verifyChecksum(request: JdcrDownloadRequest, actual: String?) {
        val expected = request.normalizedExpectedSha256 ?: return
        if (actual != expected) {
            resetPartial(request)
            throw JdcrDownloadChecksumException(expected, actual.orEmpty())
        }
    }

    private fun responseException(response: HttpResponse): ResponseException {
        val responseText = "Download failed with ${response.status}"
        return when (response.status.value) {
            in 300..399 -> RedirectResponseException(response, responseText)
            in 400..499 -> ClientRequestException(response, responseText)
            in 500..599 -> ServerResponseException(response, responseText)
            else -> ResponseException(response, responseText)
        }
    }

    private fun HttpResponse.downloadValidator(): DownloadValidator? {
        val etag = headers[HttpHeaders.ETag]
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.startsWith("W/", ignoreCase = true) }
        if (etag != null) return DownloadValidator(ValidatorKind.ETAG, etag)

        return headers[HttpHeaders.LastModified]
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { DownloadValidator(ValidatorKind.LAST_MODIFIED, it) }
    }

    private fun writeMetadata(file: File, metadata: DownloadMetadata) {
        val properties = Properties().apply {
            setProperty(METADATA_SOURCE, metadata.sourceKey)
            setProperty(METADATA_VALIDATOR_KIND, metadata.validator.kind.name)
            setProperty(METADATA_VALIDATOR_VALUE, metadata.validator.value)
        }
        FileOutputStream(file).use { output ->
            properties.store(output, null)
            output.fd.sync()
        }
    }

    private fun readMetadata(file: File): DownloadMetadata? = runCatching {
        if (!file.isFile) return null
        val properties = Properties()
        FileInputStream(file).use(properties::load)
        DownloadMetadata(
            sourceKey = properties.getProperty(METADATA_SOURCE) ?: return null,
            validator = DownloadValidator(
                kind = ValidatorKind.valueOf(
                    properties.getProperty(METADATA_VALIDATOR_KIND) ?: return null
                ),
                value = properties.getProperty(METADATA_VALIDATOR_VALUE) ?: return null,
            )
        )
    }.getOrNull()

    private fun sourceKey(url: String): String = MessageDigest.getInstance(SHA_256)
        .digest(url.toByteArray(Charsets.UTF_8))
        .toHexString()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance(SHA_256)
        digest.updateFrom(file)
        return digest.digest().toHexString()
    }

    private fun MessageDigest.updateFrom(file: File) {
        FileInputStream(file).use { input ->
            val buffer = ByteArray(JdcrDownloadRequest.DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                update(buffer, 0, read)
            }
        }
    }

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private data class ResumeContext(
        val offset: Long,
        val metadata: DownloadMetadata?,
    )

    private data class DownloadMetadata(
        val sourceKey: String,
        val validator: DownloadValidator,
    )

    private data class DownloadValidator(
        val kind: ValidatorKind,
        val value: String,
    )

    private enum class ValidatorKind {
        ETAG,
        LAST_MODIFIED,
    }

    private enum class RequestOutcome {
        Completed,
        RestartFromZero,
    }

    companion object {
        private const val SHA_256 = "SHA-256"
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val METADATA_SOURCE = "source"
        private const val METADATA_VALIDATOR_KIND = "validatorKind"
        private const val METADATA_VALIDATOR_VALUE = "validatorValue"
    }
}

internal data class ParsedContentRange(
    val start: Long,
    val endInclusive: Long,
    val total: Long?,
)

internal fun parseContentRange(value: String?): ParsedContentRange? {
    val match = value?.trim()?.let(CONTENT_RANGE_REGEX::matchEntire) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
    if (end < start || total != null && end >= total) return null
    return ParsedContentRange(start, end, total)
}

internal fun parseUnsatisfiedContentRange(value: String?): Long? {
    val match = value?.trim()?.let(UNSATISFIED_CONTENT_RANGE_REGEX::matchEntire) ?: return null
    return match.groupValues[1].toLongOrNull()
}

private val CONTENT_RANGE_REGEX = Regex(
    pattern = "^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$",
    option = RegexOption.IGNORE_CASE,
)
private val UNSATISFIED_CONTENT_RANGE_REGEX = Regex(
    pattern = "^bytes\\s+\\*/(\\d+)$",
    option = RegexOption.IGNORE_CASE,
)
