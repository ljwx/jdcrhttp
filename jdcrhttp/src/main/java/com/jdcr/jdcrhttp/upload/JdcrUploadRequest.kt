package com.jdcr.jdcrhttp.upload

import com.jdcr.jdcrhttp.request.JdcrMediaType
import java.io.File

enum class JdcrUploadMethod {
    POST,
    PUT,
    PATCH,
}

/** 单个 multipart 文件字段。 */
data class JdcrUploadFilePart(
    val file: File,
    val fieldName: String = "file",
    val fileName: String = file.name,
    /** null 时按扩展名猜测，猜不到则使用 application/octet-stream。 */
    val contentType: String? = null,
) {
    init {
        require(fieldName.isNotBlank()) { "fieldName 不能为空" }
        require(fileName.isNotBlank()) { "fileName 不能为空" }
        require(contentType == null || contentType.isNotBlank()) {
            "contentType 不能为空白字符串"
        }
    }
}

sealed interface JdcrUploadBody {

    /** multipart/form-data：一个或多个文件 + 可选文本字段。 */
    data class Multipart(
        val files: List<JdcrUploadFilePart>,
        val fields: List<Pair<String, String>> = emptyList(),
    ) : JdcrUploadBody {
        init {
            require(files.isNotEmpty()) { "multipart 至少需要一个文件" }
            fields.forEach { (name, _) ->
                require(name.isNotBlank()) { "form 字段名不能为空" }
            }
        }
    }

    /** 原始二进制请求体，适合 PUT/POST 直接传文件。 */
    data class Binary(
        val file: File,
        val contentType: String = JdcrMediaType.BINARY,
        /** 若设置，会附加 Content-Disposition: attachment; filename=... */
        val fileName: String? = file.name,
    ) : JdcrUploadBody {
        init {
            require(contentType.isNotBlank()) { "contentType 不能为空" }
            require(fileName == null || fileName.isNotBlank()) {
                "fileName 不能为空白字符串"
            }
        }
    }
}

/**
 * 文件上传请求。
 *
 * 大文件走流式上传，不把整文件读入内存。取消收集会取消进行中的请求。
 */
data class JdcrUploadRequest(
    val pathOrUrl: String,
    val body: JdcrUploadBody,
    val method: JdcrUploadMethod = JdcrUploadMethod.POST,
    val progressIntervalMillis: Long = 200L,
    val bufferSize: Int = DEFAULT_BUFFER_SIZE,
    /** 最大上传速度（字节/秒）；为 null 时不限速。 */
    val maxBytesPerSecond: Long? = null,
) {
    init {
        require(pathOrUrl.isNotBlank()) { "pathOrUrl 不能为空" }
        require(progressIntervalMillis > 0) { "progressIntervalMillis 必须大于 0" }
        require(bufferSize in MIN_BUFFER_SIZE..MAX_BUFFER_SIZE) {
            "bufferSize 必须在 $MIN_BUFFER_SIZE..$MAX_BUFFER_SIZE 之间"
        }
        require(maxBytesPerSecond == null || maxBytesPerSecond > 0L) {
            "maxBytesPerSecond 必须大于 0"
        }
    }

    internal val files: List<File>
        get() = when (val uploadBody = body) {
            is JdcrUploadBody.Multipart -> uploadBody.files.map { it.file }
            is JdcrUploadBody.Binary -> listOf(uploadBody.file)
        }

    companion object {
        const val DEFAULT_BUFFER_SIZE = 64 * 1024
        const val MIN_BUFFER_SIZE = 8 * 1024
        const val MAX_BUFFER_SIZE = 1024 * 1024

        /** 单文件 multipart 便捷构造。 */
        fun multipart(
            pathOrUrl: String,
            file: File,
            fieldName: String = "file",
            fileName: String = file.name,
            contentType: String? = null,
            fields: Map<String, String> = emptyMap(),
            method: JdcrUploadMethod = JdcrUploadMethod.POST,
            progressIntervalMillis: Long = 200L,
            bufferSize: Int = DEFAULT_BUFFER_SIZE,
            maxBytesPerSecond: Long? = null,
        ): JdcrUploadRequest = JdcrUploadRequest(
            pathOrUrl = pathOrUrl,
            body = JdcrUploadBody.Multipart(
                files = listOf(
                    JdcrUploadFilePart(
                        file = file,
                        fieldName = fieldName,
                        fileName = fileName,
                        contentType = contentType,
                    )
                ),
                fields = fields.toList(),
            ),
            method = method,
            progressIntervalMillis = progressIntervalMillis,
            bufferSize = bufferSize,
            maxBytesPerSecond = maxBytesPerSecond,
        )

        /** 原始二进制便捷构造，默认 PUT。 */
        fun binary(
            pathOrUrl: String,
            file: File,
            contentType: String = JdcrMediaType.BINARY,
            fileName: String? = file.name,
            method: JdcrUploadMethod = JdcrUploadMethod.PUT,
            progressIntervalMillis: Long = 200L,
            bufferSize: Int = DEFAULT_BUFFER_SIZE,
            maxBytesPerSecond: Long? = null,
        ): JdcrUploadRequest = JdcrUploadRequest(
            pathOrUrl = pathOrUrl,
            body = JdcrUploadBody.Binary(
                file = file,
                contentType = contentType,
                fileName = fileName,
            ),
            method = method,
            progressIntervalMillis = progressIntervalMillis,
            bufferSize = bufferSize,
            maxBytesPerSecond = maxBytesPerSecond,
        )
    }
}
