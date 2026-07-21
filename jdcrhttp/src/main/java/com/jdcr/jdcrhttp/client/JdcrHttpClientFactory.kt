package com.jdcr.jdcrhttp.client

import com.jdcr.jdcrhttp.auth.JdcrHttpAuthUtils
import com.jdcr.jdcrhttp.config.JdcrHttpConfig
import com.jdcr.jdcrhttp.proxy.JdcrTrustAllManager
import com.jdcr.jdcrhttp.serialization.JdcrJsonCodec
import com.jdcr.jdcrhttp.util.JdcrHttpLog
import com.jdcr.jdcrhttp.util.JdcrHttpUtils
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.CIOEngineConfig
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerAuthProvider
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.plugin
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.network.sockets.SocketTimeoutException
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException

internal fun JdcrHttpLogLevel.toLevel(): LogLevel {
    return when (this) {
        JdcrHttpLogLevel.ALL -> LogLevel.ALL
        JdcrHttpLogLevel.BODY -> LogLevel.BODY
        JdcrHttpLogLevel.HEADERS -> LogLevel.HEADERS
        JdcrHttpLogLevel.NONE -> LogLevel.NONE
        else -> LogLevel.INFO
    }
}

object JdcrHttpClientFactory {

    private var bearerProvider: BearerAuthProvider? = null

    internal fun clearBearerTokenCache() {
        bearerProvider?.clearToken()
    }

    fun getDefaultHttp(
        config: JdcrHttpConfig = JdcrHttpConfig(), // 聚合配置：超时、引擎、重定向、重试、序列化、日志等
        configureEngine: CIOEngineConfig.() -> Unit = {}, // CIO 引擎追加配置（TLS/https、线程数、proxy 覆盖等）
        configureClient: HttpClientConfig<*>.() -> Unit = {}, // Ktor 客户端追加配置（插件、defaultRequest、校验器等）
    ): HttpClient {
        return HttpClient(CIO) {
            expectSuccess = true // true：非 2xx 直接抛 ResponseException,不然404会走到成功response

            engine {
                config.proxy?.let { proxy = it } // HTTP/SOCKS 代理；null 表示直连
                if (config.trustAllCertificates) {
                    https {
                        trustManager = JdcrTrustAllManager
                    }
                }
                maxConnectionsCount = config.engine.maxConnectionsCount // CIO 全局最大并发连接数（整客户端）
                requestTimeout = 0 // 引擎不限整请求时长，墙钟超时交给 HttpTimeout 插件，SSE 才能长连
                endpoint {
                    connectTimeout = config.timeout.connectTimeoutMs // 建连（TCP/TLS）阶段超时
                    socketTimeout = config.timeout.socketTimeoutMs // 已连接后，相邻两次读写到时的空闲超时
                    connectAttempts = config.engine.retryTimes + 1 // 建连失败时最多尝试次数（含首次）
                    maxConnectionsPerRoute =
                        config.engine.maxConnectionsPerRoute // 同一 host（同域名）最大并行连接数
                    pipelineMaxSize = config.engine.pipelineMaxSize // HTTP 管线：单连接上未等响应可排队请求数
                    keepAliveTime = config.engine.keepAliveTimeMs // 空闲连接保持时长，超时可回收
                    allowHalfClose = config.engine.allowHalfClose // TCP 半关闭：一般保持 false
                }
                configureEngine() // 外层传入：在默认引擎参数之后再改 CIOEngineConfig
            }

            followRedirects = config.redirect.enabled
            if (config.redirect.enabled) {
                install(HttpRedirect) {
                    allowHttpsDowngrade =
                        config.redirect.allowHttpsDowngrade // 是否允许 https 跳转到 http（不安全，通常 false）
                    checkHttpMethod = config.redirect.checkHttpMethod // 是否校验允许隐式重定向的 HTTP 方法
                }
            }

            if (config.retry.enabled) {
                install(HttpRequestRetry) {
                    maxRetries = config.retry.maxRetries // 插件层最大重试次数（与引擎建连重试无关）
                    fun canRetryMethod(method: HttpMethod): Boolean {
                        return when (method) {
                            HttpMethod.Post, HttpMethod.Patch -> return false
                            else -> true
                        }
                    }
                    retryIf { request, response ->
                        val codeRange = response.status.value in 500..599
                        config.retry.retryOn5xx && codeRange && canRetryMethod(request.method)
                    }
                    retryOnExceptionIf { request, cause ->
                        val causeRange =
                            cause is IOException || cause is ConnectTimeoutException || cause is SocketTimeoutException || cause is HttpRequestTimeoutException
                        config.retry.retryOnNetworkError && causeRange && canRetryMethod(request.method) // IOException 时是否重试
                    }
                    exponentialDelay() // 重试间隔：指数退避
                }
            }

            install(HttpTimeout) {
                connectTimeoutMillis = config.timeout.connectTimeoutMs // 插件层：建连超时（与引擎 endpoint 对齐）
                socketTimeoutMillis = config.timeout.socketTimeoutMs // 插件层：socket 读写空闲超时
                requestTimeoutMillis = config.timeout.requestTimeoutMs // 插件层：整次请求墙钟上限
            }

            install(ContentNegotiation) {
                val custom = config.json.configureContent
                if (custom != null) {
                    custom() // 自定义序列化（非 null 时忽略下方默认 Json）
                } else {
                    json(
                        JdcrJsonCodec.API_JSON,
                        contentType = ContentType.Application.Json,
                    )
                }
            }

            if (config.compression.enabled) {
                install(ContentEncoding) {
                    gzip() // 声明支持 gzip 解压响应
                    deflate() // 声明支持 deflate 解压响应
                }
            }

            if (config.cookies.enabled) {
                install(HttpCookies) {
                    storage = AcceptAllCookiesStorage() // 内存 Cookie；持久化需换 Storage
                }
            }

            defaultRequest {
                val ua = config.headers.userAgent.trim()
                if (ua.isNotEmpty()) {
                    header(HttpHeaders.UserAgent, ua) // 默认 User-Agent
                }
                config.headers.headers.forEach { (name, value) ->
                    header(name, value) // 默认静态 Header（勿塞动态 Token）
                }
            }

            install(Logging) {
                level = config.log.level.toLevel()
                logger = object : Logger {
                    override fun log(message: String) {
                        val output = JdcrHttpUtils.sanitizeLogMessage(message, "****")
                        JdcrHttpLog.i(output) // 输出到项目日志
                    }
                }
            }

            if (config.auth.enable && config.auth.enableDefaultPlugin) {
                bearerProvider = BearerAuthProvider(loadTokens = {
                    val provider = config.auth.provider
                    JdcrHttpAuthUtils.getBearerTokens(provider?.invoke(), null)
                }, sendWithoutRequestCallback = { request ->
                    JdcrHttpAuthUtils.handlePluginToken(request, config.auth.enableTokenAll)
                }, realm = null, refreshTokens = {
//                    delay(300)
//                    JdcrHttpAuthUtils.setGlobalToken("111")
//                    JdcrHttpAuthUtils.getBearerTokens("111", null)
                    null
                })

                install(Auth) {
                    bearerProvider?.let { providers += it }
                }
            }

            configureClient() // 外层传入：在所有内置插件之后追加 Ktor 配置

        }.also { client ->
            if (config.auth.enable && !config.auth.enableDefaultPlugin) {
                client.plugin(HttpSend).intercept { original ->
                    val key = config.auth.disableDefaultAndCustomKey ?: "Authorization"
                    JdcrHttpAuthUtils.handleCustomToken(
                        original,
                        config.auth.enableTokenAll,
                        key,
                        config.auth.provider
                    )
                    execute(original)
                }
            }
        }
    }
}
