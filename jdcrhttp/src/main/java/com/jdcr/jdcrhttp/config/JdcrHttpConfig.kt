package com.jdcr.jdcrhttp.config

import com.jdcr.jdcrhttp.client.JdcrHttpLogLevel
import io.ktor.client.engine.ProxyConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation

/**
 * CIO 引擎连接池与 endpoint 占位；可按环境与机型调优。
 */
data class JdcrHttpConfigEngine(
    val maxConnectionsCount: Int = 64,
    /** 建连失败时的额外尝试次数语义：`connectAttempts = retryTimes + 1` */
    val retryTimes: Int = 0,
    val maxConnectionsPerRoute: Int = 32,
    val pipelineMaxSize: Int = 20,
    val keepAliveTimeMs: Long = 30_000L,
    val allowHalfClose: Boolean = false,
) {
    init {
        require(retryTimes >= 0) { "retryTimes 不能小于 0" }
        require(maxConnectionsCount > 0)
        require(maxConnectionsPerRoute > 0)
        require(pipelineMaxSize > 0)
    }
}

data class JdcrHttpConfigTimeout(
    val connectTimeoutMs: Long = 15_000L,
    val socketTimeoutMs: Long = 30_000L,
    val requestTimeoutMs: Long = 30_000L,
) {
    init {
        require(connectTimeoutMs > 0)
        require(socketTimeoutMs > 0)
        require(requestTimeoutMs > 0)
    }
}

data class JdcrHttpConfigLog(
    var level: JdcrHttpLogLevel = JdcrHttpLogLevel.INFO,
)

/**
 * HTTP 重试占位（插件层，与引擎 [JdcrHttpConfigEngine.retryTimes] 不同）。
 */
data class JdcrHttpConfigRetry(
    val enabled: Boolean = true,
    val maxRetries: Int = 0,
    /** 是否对 5xx 响应重试 */
    var retryOn5xx: Boolean = false,
    /** 是否在 IOException 等传输异常时重试（占位，后续可按异常类型收紧） */
    var retryOnNetworkError: Boolean = true,
) {
    init {
        require(maxRetries >= 0) { "maxRetries 不能小于 0" }
    }
}

/** 301/302 等重定向 */
data class JdcrHttpConfigRedirect(
    val enabled: Boolean = false,
    val allowHttpsDowngrade: Boolean = false,
    val checkHttpMethod: Boolean = true,
)

/**
 * [defaultRequest] 占位：公共 Header；token 等动态头仍建议走 Auth 或单次请求块。
 */
data class JdcrHttpConfigHeaders(
    /** 占位 UA，上线请换成真实应用标识 */
    var userAgent: String = "Android; ktor",
    var headers: Map<String, String> = emptyMap(),
)

data class JdcrHttpConfigAuth(
    val enable: Boolean = true,
    val enableDefaultPlugin: Boolean = true,
    val enableTokenAll: Boolean = true, //所有接口默认添加token
    var disableDefaultAndCustomKey: String? = null,
    var provider: (suspend () -> String?)? = null,
)

/** Cookie：占位默认内存存储；持久化可之后在工厂里换 [PersistentCookieStorage] 等 */
data class JdcrHttpConfigCookies(
    val enabled: Boolean = false,
)

/** Accept-Encoding：gzip / deflate 等（需 ktor-client-encoding） */
data class JdcrHttpConfigCompression(
    val enabled: Boolean = false,
)

data class JdcrHttpConfigContentJson(
    val configureContent: (ContentNegotiation.Config.() -> Unit)? = null,
)

/**
 * 聚合配置：字段均为占位默认值；你可整体替换某一组或在业务模块里 `copy(...)`。
 *
 * **序列化**：默认使用 **kotlinx.serialization JSON**。请求/响应体用到的 Kotlin 类型需标注 **`@Serializable`**（或按需自定义序列化）。
 *
 * HTTPS 证书锁定、自定义 DNS 等仍在 [configureEngine] / [configureClient] 中按需编写。
 */
data class JdcrHttpConfig(
    val engine: JdcrHttpConfigEngine = JdcrHttpConfigEngine(),
    val timeout: JdcrHttpConfigTimeout = JdcrHttpConfigTimeout(),
    val log: JdcrHttpConfigLog = JdcrHttpConfigLog(),
    val retry: JdcrHttpConfigRetry = JdcrHttpConfigRetry(),
    val headers: JdcrHttpConfigHeaders = JdcrHttpConfigHeaders(),
    val auth: JdcrHttpConfigAuth = JdcrHttpConfigAuth(),
    val redirect: JdcrHttpConfigRedirect = JdcrHttpConfigRedirect(),
    val cookies: JdcrHttpConfigCookies = JdcrHttpConfigCookies(),
    val compression: JdcrHttpConfigCompression = JdcrHttpConfigCompression(),
    /** 占位：无需代理时为 null；示例：`ProxyBuilder.http("127.0.0.1", 8888)` */
    val proxy: ProxyConfig? = null,
    val json: JdcrHttpConfigContentJson = JdcrHttpConfigContentJson()
)
