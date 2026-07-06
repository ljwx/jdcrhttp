package com.jdcr.jdcrhttp

import com.jdcr.jdcrhttp.JdcrHttpManager.Companion.manager
import com.jdcr.jdcrhttp.client.JdcrHttpClientFactory
import com.jdcr.jdcrhttp.response.JdcrHttpResult
import com.jdcr.jdcrhttp.response.handleRequestResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.put
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

open class JdcrHttpCore(
    @PublishedApi internal open var client: HttpClient,
    override val baseUrl: String,
) : IJdcrHttpManager {

    @PublishedApi
    internal val sseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun clearBearerTokenCache() {
        JdcrHttpClientFactory.clearBearerTokenCache()
    }

    suspend inline fun <reified T> get(
        pathOrUrl: String,
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ): JdcrHttpResult<T> = handleRequestResult<T>(pathOrUrl) {
        client.get {
            url(resolveUrl(pathOrUrl))
            block()
        }.body()
    }

    @PublishedApi
    internal fun HttpRequestBuilder.sseRequestConfig(pathOrUrl: String) {
        timeout {
            requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
        }
        url(resolveUrl(pathOrUrl))
        header(HttpHeaders.Accept, "text/event-stream")
        header(HttpHeaders.CacheControl, "no-cache")
        header(HttpHeaders.AcceptEncoding, "identity")
    }

    @PublishedApi
    internal suspend inline fun sseResponseHandler(
        response: HttpResponse,
        onLine: suspend (String) -> Unit,
        onClosed: suspend () -> Unit
    ) {
        val channel = response.bodyAsChannel()
        while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line(Int.MAX_VALUE) ?: break
            onLine(line)
        }
        onClosed()
    }

    suspend inline fun getSSE(
        pathOrUrl: String,
        crossinline block: HttpRequestBuilder.() -> Unit = {},
        crossinline onLine: suspend (String) -> Unit,
        crossinline onClosed: suspend () -> Unit,
    ): JdcrHttpResult<Unit> = handleRequestResult(pathOrUrl) {
        client.prepareGet {
            sseRequestConfig(pathOrUrl)
            block()
        }.execute { response ->
            sseResponseHandler(response, onLine, onClosed)
        }
    }

    suspend inline fun <reified T> post(
        pathOrUrl: String,
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ): JdcrHttpResult<T> = handleRequestResult<T>(pathOrUrl) {
        client.post {
            url(resolveUrl(pathOrUrl))
            block()
        }.body()
    }

    suspend inline fun postSSE(
        pathOrUrl: String,
        crossinline block: HttpRequestBuilder.() -> Unit = {},
        crossinline onLine: suspend (String) -> Unit,
        crossinline onClosed: suspend () -> Unit,
    ): JdcrHttpResult<Unit> = handleRequestResult(pathOrUrl) {
        client.preparePost {
            sseRequestConfig(pathOrUrl)
            block()
        }.execute { response ->
            sseResponseHandler(response, onLine, onClosed)
        }
    }

    suspend inline fun <reified T> put(
        pathOrUrl: String,
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ): JdcrHttpResult<T> = handleRequestResult<T>(pathOrUrl) {
        client.put {
            url(resolveUrl(pathOrUrl))
            block()
        }.body()
    }

    suspend inline fun <reified T> patch(
        pathOrUrl: String,
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ): JdcrHttpResult<T> = handleRequestResult<T>(pathOrUrl) {
        client.patch {
            url(resolveUrl(pathOrUrl))
            block()
        }.body()
    }

    suspend inline fun <reified T> delete(
        pathOrUrl: String,
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ): JdcrHttpResult<T> = handleRequestResult<T>(pathOrUrl) {
        client.delete {
            url(resolveUrl(pathOrUrl))
            block()
        }.body()
    }

//    suspend fun getRaw(
//        pathOrUrl: String,
//        block: HttpRequestBuilder.() -> Unit = {},
//    ): HttpResponse = client.get {
//        url(resolveUrl(pathOrUrl))
//        block()
//    }
//
//    suspend fun postRaw(
//        pathOrUrl: String,
//        block: HttpRequestBuilder.() -> Unit = {},
//    ): HttpResponse = client.post {
//        url(resolveUrl(pathOrUrl))
//        block()
//    }
//
//    suspend fun putRaw(
//        pathOrUrl: String,
//        block: HttpRequestBuilder.() -> Unit = {},
//    ): HttpResponse = client.put {
//        url(resolveUrl(pathOrUrl))
//        block()
//    }
//
//    suspend fun patchRaw(
//        pathOrUrl: String,
//        block: HttpRequestBuilder.() -> Unit = {},
//    ): HttpResponse = client.patch {
//        url(resolveUrl(pathOrUrl))
//        block()
//    }
//
//    suspend fun deleteRaw(
//        pathOrUrl: String,
//        block: HttpRequestBuilder.() -> Unit = {},
//    ): HttpResponse = client.delete {
//        url(resolveUrl(pathOrUrl))
//        block()
//    }
//
//    suspend fun getText(
//        pathOrUrl: String,
//        block: HttpRequestBuilder.() -> Unit = {},
//    ): String = getRaw(pathOrUrl, block).bodyAsText()

    override fun destroyClient() {
        sseScope.cancel()
        client.close()
        manager = null
    }

}