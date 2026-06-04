package com.jdcr.jdcrhttp

import com.jdcr.jdcrhttp.response.JdcrHttpResult
import com.jdcr.jdcrhttp.response.handleRequestResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.core.Closeable
import kotlinx.coroutines.CancellationException

class JdcrHttpManager(
    val client: HttpClient,
    private val baseUrl: String = "",
) : Closeable, IJdcrHttpManager {

    companion object {
        @Volatile
        private var manager: JdcrHttpManager? = null
        fun initInstance(
            baseUrl: String,
            client: HttpClient = JdcrHttpClientFactory.getDefaultHttp()
        ): JdcrHttpManager {
            manager?.let { return it }
            return synchronized(this) {
                manager ?: JdcrHttpManager(client, baseUrl)
            }
        }

        fun instance(): JdcrHttpManager {
            return requireNotNull(manager) {
                "请先初始化JdcrHttpManager.initInstance()"
            }
        }
    }

    fun clearBearerTokenCache() {
        JdcrHttpClientFactory.clearBearerTokenCache()
    }

    fun resolveUrl(pathOrUrl: String): String {
        val trimmed = pathOrUrl.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        val base = baseUrl.trimEnd('/')
        if (base.isEmpty()) return trimmed.trimStart('/')
        val path = trimmed.trimStart('/')
        return "$base/$path"
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

    suspend inline fun connectSSE(
        pathOrUrl: String,
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ): ByteReadChannel = client.get {
        timeout {
            requestTimeoutMillis = 0
        }
        url(resolveUrl(pathOrUrl))
        header(HttpHeaders.Accept, "text/event-stream")
        header(HttpHeaders.CacheControl, "no-cache")
        block()
    }.bodyAsChannel()

    suspend inline fun <reified T> post(
        pathOrUrl: String,
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ): JdcrHttpResult<T> = handleRequestResult<T>(pathOrUrl) {
        client.post {
            url(resolveUrl(pathOrUrl))
            block()
        }.body()
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

    override fun close() {
        client.close()
    }

}

suspend inline fun <T> JdcrHttpManager.runCatchingCancellable(crossinline block: suspend JdcrHttpManager.() -> T): Result<T> {
    return try {
        Result.success(block(this))
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Throwable) {
        Result.failure(e)
    }
}
