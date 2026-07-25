package com.jdcr.jdcrhttp.download

import java.io.File

/** 文件下载请求。临时文件校验通过后才会替换 [destination]。 */
data class JdcrDownloadRequest(
    val pathOrUrl: String,
    val destination: File,
    val resume: Boolean = true,
    val overwriteExisting: Boolean = false,
    val expectedSha256: String? = null,
    val calculateSha256: Boolean = expectedSha256 != null,
    val progressIntervalMillis: Long = 200L,
    val bufferSize: Int = DEFAULT_BUFFER_SIZE,
) {
    init {
        require(pathOrUrl.isNotBlank()) { "pathOrUrl 不能为空" }
        require(progressIntervalMillis > 0) { "progressIntervalMillis 必须大于 0" }
        require(bufferSize in MIN_BUFFER_SIZE..MAX_BUFFER_SIZE) {
            "bufferSize 必须在 $MIN_BUFFER_SIZE..$MAX_BUFFER_SIZE 之间"
        }
        require(expectedSha256 == null || SHA_256_REGEX.matches(expectedSha256.trim())) {
            "expectedSha256 必须是 64 位十六进制字符串"
        }
    }

    internal val normalizedExpectedSha256: String?
        get() = expectedSha256?.trim()?.lowercase()

    internal val partialFile: File
        get() = File(destination.absoluteFile.parentFile, "${destination.name}.jdcr.part")

    internal val metadataFile: File
        get() = File(destination.absoluteFile.parentFile, "${destination.name}.jdcr.part.meta")

    companion object {
        const val DEFAULT_BUFFER_SIZE = 64 * 1024
        const val MIN_BUFFER_SIZE = 8 * 1024
        const val MAX_BUFFER_SIZE = 1024 * 1024

        private val SHA_256_REGEX = Regex("^[0-9a-fA-F]{64}$")
    }
}
