package com.jdcr.jdcrhttp.upload

import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream

internal fun resolveContentType(part: JdcrUploadFilePart): ContentType {
    part.contentType?.let { return ContentType.parse(it) }
    return guessContentType(part.fileName)
}

internal fun guessContentType(fileName: String): ContentType {
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
    return when (extension) {
        "jpg", "jpeg" -> ContentType.Image.JPEG
        "png" -> ContentType.Image.PNG
        "gif" -> ContentType.Image.GIF
        "webp" -> ContentType("image", "webp")
        "bmp" -> ContentType("image", "bmp")
        "svg" -> ContentType.Image.SVG
        "mp4" -> ContentType.Video.MP4
        "mp3" -> ContentType.Audio.MPEG
        "wav" -> ContentType("audio", "wav")
        "pdf" -> ContentType.Application.Pdf
        "json" -> ContentType.Application.Json
        "txt", "log", "csv" -> ContentType.Text.Plain
        "html", "htm" -> ContentType.Text.Html
        "xml" -> ContentType.Application.Xml
        "zip" -> ContentType.Application.Zip
        "gz" -> ContentType.Application.GZip
        "apk" -> ContentType("application", "vnd.android.package-archive")
        else -> ContentType.Application.OctetStream
    }
}

internal fun escapeFileName(fileName: String): String =
    fileName.replace("\\", "\\\\").replace("\"", "\\\"")

/**
 * 原始二进制文件体，按块读取并回调进度。
 */
internal class BinaryFileOutgoingContent(
    private val file: File,
    override val contentType: ContentType,
    fileName: String?,
    private val bufferSize: Int,
    private val onBytesWritten: suspend (Int) -> Unit,
) : OutgoingContent.WriteChannelContent() {

    override val contentLength: Long = file.length()

    override val headers: Headers = if (fileName.isNullOrBlank()) {
        Headers.Empty
    } else {
        Headers.build {
            append(
                HttpHeaders.ContentDisposition,
                "attachment; filename=\"${escapeFileName(fileName)}\""
            )
        }
    }

    override suspend fun writeTo(channel: ByteWriteChannel) {
        FileInputStream(file).use { input ->
            val buffer = ByteArray(bufferSize)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                onBytesWritten(read)
                channel.writeFully(buffer, 0, read)
            }
        }
        channel.flush()
    }
}

/**
 * 包装任意 WriteChannelContent，在真正写出到网络前统计字节并回调。
 * 利用 ByteChannel 背压，避免限速时把整文件提前读入内存。
 */
@Suppress("DEPRECATION")
internal class ProgressTrackingContent(
    private val delegate: OutgoingContent.WriteChannelContent,
    private val bufferSize: Int,
    private val onBytesWritten: suspend (Int) -> Unit,
) : OutgoingContent.WriteChannelContent() {

    override val contentType: ContentType? get() = delegate.contentType
    override val contentLength: Long? get() = delegate.contentLength
    override val headers: Headers get() = delegate.headers
    override val status get() = delegate.status

    override suspend fun writeTo(channel: ByteWriteChannel) {
        val intermediate = ByteChannel(autoFlush = true)
        coroutineScope {
            val reader = launch {
                val buffer = ByteArray(bufferSize)
                try {
                    while (!intermediate.isClosedForRead) {
                        val read = intermediate.readAvailable(buffer)
                        if (read == -1) break
                        if (read == 0) continue
                        onBytesWritten(read)
                        channel.writeFully(buffer, 0, read)
                    }
                    channel.flush()
                } catch (error: Throwable) {
                    intermediate.close(error)
                    throw error
                }
            }

            try {
                delegate.writeTo(intermediate)
            } catch (error: Throwable) {
                intermediate.close(error)
                throw error
            } finally {
                if (!intermediate.isClosedForWrite) {
                    intermediate.close()
                }
            }

            reader.join()
        }
    }
}
