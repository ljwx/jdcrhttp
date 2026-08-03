package com.jdcr.jdcrhttp

import com.jdcr.jdcrhttp.JdcrHttpManager.Companion.manager
import com.jdcr.jdcrhttp.client.JdcrHttpClientFactory
import com.jdcr.jdcrhttp.download.JdcrDownloadRequest
import com.jdcr.jdcrhttp.download.JdcrDownloadState
import com.jdcr.jdcrhttp.download.JdcrFileDownloader
import com.jdcr.jdcrhttp.request.JdcrRequestBuilder
import com.jdcr.jdcrhttp.request.JdcrRequestOptions
import com.jdcr.jdcrhttp.request.applyJdcrRequest
import com.jdcr.jdcrhttp.response.JdcrHttpResult
import com.jdcr.jdcrhttp.response.handleRequestResult
import com.jdcr.jdcrhttp.response.readSseLine
import com.jdcr.jdcrhttp.upload.JdcrFileUploader
import com.jdcr.jdcrhttp.upload.JdcrUploadRequest
import com.jdcr.jdcrhttp.upload.JdcrUploadState
import com.jdcr.jdcrhttp.util.JdcrHttpLog
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
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.HttpHeaders
import io.ktor.util.reflect.TypeInfo
import io.ktor.util.reflect.typeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlin.reflect.KClass

open class JdcrHttpCore(
    @PublishedApi internal open var client: HttpClient,
    override val baseUrl: String,
) : IJdcrHttpManager {

    @PublishedApi
    internal val sseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val fileDownloader = JdcrFileDownloader(
        clientProvider = { client },
        resolveUrl = ::resolveUrl,
    )

    private val fileUploader = JdcrFileUploader(
        clientProvider = { client },
        resolveUrl = ::resolveUrl,
    )

    fun clearBearerTokenCache() {
        JdcrHttpClientFactory.clearBearerTokenCache()
    }

    /** 返回冷 Flow；取消收集会取消请求，并保留可安全续传的临时文件。 */
    fun downloadFile(
        request: JdcrDownloadRequest,
        block: JdcrRequestBuilder.() -> Unit = {},
    ): Flow<JdcrDownloadState> {
        val options = JdcrRequestBuilder()
            .apply(block)
            .build()
        return fileDownloader.download(request, options)
    }

    /**
     * 返回冷 Flow；取消收集会取消进行中的上传。
     *
     * [JdcrRequestBuilder] 可用于 Header / Query / Timeout / Auth；
     * 请求体由 [JdcrUploadRequest] 决定，builder 里的 body 会被忽略。
     *
     * 响应类型 [T] 支持 data class（JSON）、[String]、[ByteArray]、[Unit]。
     */
    inline fun <reified T> uploadFile(
        request: JdcrUploadRequest,
        noinline block: JdcrRequestBuilder.() -> Unit = {},
    ): Flow<JdcrUploadState<T>> {
        val options = JdcrRequestBuilder()
            .apply(block)
            .build()
        val responseType = T::class
        val responseTypeInfo = typeInfo<T>()
        return uploadFileInternal(request, options) { response ->
            parseUploadResponse(response, responseType, responseTypeInfo)
        }
    }

    @PublishedApi
    internal fun <T> uploadFileInternal(
        request: JdcrUploadRequest,
        options: JdcrRequestOptions,
        parseResponse: suspend (HttpResponse) -> T,
    ): Flow<JdcrUploadState<T>> = fileUploader.upload(request, options, parseResponse)

    @PublishedApi
    internal suspend fun <T> parseUploadResponse(
        response: HttpResponse,
        type: KClass<*>,
        typeInfo: TypeInfo,
    ): T {
        @Suppress("UNCHECKED_CAST")
        return when (type) {
            Unit::class -> Unit as T
            String::class -> response.bodyAsText() as T
            ByteArray::class -> response.readBytes() as T
            else -> response.body(typeInfo)
        }
    }

    suspend inline fun <reified T> get(
        pathOrUrl: String,
        crossinline block: JdcrRequestBuilder.() -> Unit = {},
    ): JdcrHttpResult<T> = handleRequestResult<T>(pathOrUrl) {
        client.get {
            url(resolveUrl(pathOrUrl))
            val options = JdcrRequestBuilder()
                .apply(block)
                .build()
            applyJdcrRequest(options)
        }.body()
    }

    @PublishedApi
    internal fun HttpRequestBuilder.sseRequestConfig(pathOrUrl: String) {
        timeout {
            requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
            socketTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
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
            val line = channel.readSseLine() ?: break
            onLine(line)
        }
        onClosed()
    }

    suspend inline fun getSSE(
        pathOrUrl: String,
        crossinline block: JdcrRequestBuilder.() -> Unit = {},
        crossinline onLine: suspend (String) -> Unit,
        crossinline onClosed: suspend () -> Unit,
    ): JdcrHttpResult<Unit> = handleRequestResult(pathOrUrl) {
        client.prepareGet {
            sseRequestConfig(pathOrUrl)
            val options = JdcrRequestBuilder()
                .apply(block)
                .build()
            applyJdcrRequest(options)
        }.execute { response ->
            sseResponseHandler(response, onLine, onClosed)
        }
    }

    suspend inline fun <reified T> post(
        pathOrUrl: String,
        crossinline block: JdcrRequestBuilder.() -> Unit = {},
    ): JdcrHttpResult<T> = handleRequestResult<T>(pathOrUrl) {
        client.post {
            url(resolveUrl(pathOrUrl))
            val options = JdcrRequestBuilder()
                .apply(block)
                .build()
            applyJdcrRequest(options)
        }.body()
    }

    suspend inline fun postSSE(
        pathOrUrl: String,
        crossinline block: JdcrRequestBuilder.() -> Unit = {},
        crossinline onLine: suspend (String) -> Unit,
        crossinline onClosed: suspend () -> Unit,
    ): JdcrHttpResult<Unit> = handleRequestResult(pathOrUrl) {
        client.preparePost {
            sseRequestConfig(pathOrUrl)
            val options = JdcrRequestBuilder()
                .apply(block)
                .build()
            applyJdcrRequest(options)
        }.execute { response ->
            sseResponseHandler(response, onLine, onClosed)
        }
    }

    suspend inline fun <reified T> put(
        pathOrUrl: String,
        crossinline block: JdcrRequestBuilder.() -> Unit = {},
    ): JdcrHttpResult<T> = handleRequestResult<T>(pathOrUrl) {
        client.put {
            url(resolveUrl(pathOrUrl))
            val options = JdcrRequestBuilder()
                .apply(block)
                .build()
            applyJdcrRequest(options)
        }.body()
    }

    suspend inline fun <reified T> patch(
        pathOrUrl: String,
        crossinline block: JdcrRequestBuilder.() -> Unit = {},
    ): JdcrHttpResult<T> = handleRequestResult<T>(pathOrUrl) {
        client.patch {
            url(resolveUrl(pathOrUrl))
            val options = JdcrRequestBuilder()
                .apply(block)
                .build()
            applyJdcrRequest(options)
        }.body()
    }

    suspend inline fun <reified T> delete(
        pathOrUrl: String,
        crossinline block: JdcrRequestBuilder.() -> Unit = {},
    ): JdcrHttpResult<T> = handleRequestResult<T>(pathOrUrl) {
        client.delete {
            url(resolveUrl(pathOrUrl))
            val options = JdcrRequestBuilder()
                .apply(block)
                .build()
            applyJdcrRequest(options)
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
        JdcrHttpLog.w("触发http,destroyClient")
        sseScope.cancel()
        client.close()
        if (manager === this) {
            manager = null
        }
    }

}
