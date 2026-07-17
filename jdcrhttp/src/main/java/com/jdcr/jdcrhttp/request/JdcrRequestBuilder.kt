package com.jdcr.jdcrhttp.request

import com.jdcr.jdcrhttp.serialization.JdcrJsonCodec
import java.util.Locale

@DslMarker
annotation class JdcrRequestDsl

object JdcrMediaType {
    const val JSON = "application/json; charset=utf-8"
    const val TEXT = "text/plain; charset=utf-8"
    const val BINARY = "application/octet-stream"
}

sealed interface JdcrRequestBody {

    object Empty : JdcrRequestBody

    data class Text(
        val value: String,
        val contentType: String?,
    ) : JdcrRequestBody

    data class Bytes(
        val value: ByteArray,
        val contentType: String?,
    ) : JdcrRequestBody
}

data class JdcrRequestTimeout(
    val connectTimeoutMillis: Long? = null,
    val socketTimeoutMillis: Long? = null,
    val requestTimeoutMillis: Long? = null,
) {
    init {
        requireValidTimeout(connectTimeoutMillis, "connectTimeoutMillis")
        requireValidTimeout(socketTimeoutMillis, "socketTimeoutMillis")
        requireValidTimeout(requestTimeoutMillis, "requestTimeoutMillis")
    }

    private fun requireValidTimeout(value: Long?, name: String) {
        require(value == null || value > 0) {
            "$name 必须大于 0，null 表示使用全局配置"
        }
    }
}

enum class JdcrRequestAuthMode {
    DEFAULT,

    /** 无论全局是否默认添加 Token，本次请求都需要 Token。 */
    REQUIRED,

    /** 本次请求不添加全局 Token。 */
    EXCLUDED,

    /** Authorization 由本次请求自己设置。 */
    MANUAL,
}

@PublishedApi
internal data class JdcrRequestOptions(
    val headers: Map<String, List<String>>,
    val parameters: List<Pair<String, String>>,
    val body: JdcrRequestBody,
    val timeout: JdcrRequestTimeout?,
    val authMode: JdcrRequestAuthMode,
)

inline fun <reified B> JdcrRequestBuilder.jsonBodySerialized(body: B) {
    jsonBody(JdcrJsonCodec.toJson(body).getOrThrow())
}

@JdcrRequestDsl
class JdcrRequestBuilder {

    private data class MutableHeader(
        val originalName: String,
        val values: MutableList<String>,
    )

    private val headers =
        linkedMapOf<String, MutableHeader>()

    private val parameters =
        mutableListOf<Pair<String, String>>()

    private var body: JdcrRequestBody =
        JdcrRequestBody.Empty

    private var timeout: JdcrRequestTimeout? =
        null

    private var authMode: JdcrRequestAuthMode =
        JdcrRequestAuthMode.DEFAULT

    /**
     * 设置 Header。
     *
     * Header 名称忽略大小写，已有值会被覆盖。
     */
    fun header(name: String, value: Any) {
        require(name.isNotBlank()) {
            "Header name 不能为空"
        }

        headers[normalizeHeaderName(name)] = MutableHeader(
            originalName = name,
            values = mutableListOf(value.toString()),
        )
    }

    /**
     * 追加同名 Header，例如多个 Cookie。
     */
    fun appendHeader(name: String, value: Any) {
        require(name.isNotBlank()) {
            "Header name 不能为空"
        }

        val normalizedName = normalizeHeaderName(name)

        headers.getOrPut(normalizedName) {
            MutableHeader(
                originalName = name,
                values = mutableListOf(),
            )
        }.values += value.toString()
    }

    fun removeHeader(name: String) {
        headers.remove(normalizeHeaderName(name))
    }

    /**
     * query 参数允许同名多值。
     * null 表示不添加该参数。
     */
    fun parameter(name: String, value: Any?) {
        require(name.isNotBlank()) {
            "Parameter name 不能为空"
        }

        if (value != null) {
            parameters += name to value.toString()
        }
    }

    fun jsonBody(json: String) {
        body = JdcrRequestBody.Text(
            value = json,
            contentType = JdcrMediaType.JSON,
        )
    }

    fun textBody(
        text: String,
        contentType: String? = JdcrMediaType.TEXT,
    ) {
        body = JdcrRequestBody.Text(
            value = text,
            contentType = contentType,
        )
    }

    fun byteArrayBody(
        bytes: ByteArray,
        contentType: String? = JdcrMediaType.BINARY,
    ) {
        body = JdcrRequestBody.Bytes(
            value = bytes.copyOf(),
            contentType = contentType,
        )
    }

    fun emptyBody() {
        body = JdcrRequestBody.Empty
    }

    fun timeout(
        connectTimeoutMillis: Long? = null,
        socketTimeoutMillis: Long? = null,
        requestTimeoutMillis: Long? = null,
    ) {
        timeout = JdcrRequestTimeout(
            connectTimeoutMillis = connectTimeoutMillis,
            socketTimeoutMillis = socketTimeoutMillis,
            requestTimeoutMillis = requestTimeoutMillis,
        )
    }

    fun requireAuth() {
        authMode = JdcrRequestAuthMode.REQUIRED
    }

    fun excludeAuth() {
        authMode = JdcrRequestAuthMode.EXCLUDED
    }

    fun manualAuth() {
        authMode = JdcrRequestAuthMode.MANUAL
    }

    @PublishedApi
    internal fun build(): JdcrRequestOptions {
        return JdcrRequestOptions(
            headers = headers.values.associate { header ->
                header.originalName to header.values.toList()
            },
            parameters = parameters.toList(),
            body = body,
            timeout = timeout,
            authMode = authMode,
        )
    }

    private fun normalizeHeaderName(name: String): String {
        return name.lowercase(Locale.ROOT)
    }
}

