package com.jdcr.jdcrhttp.response

import com.jdcr.jdcrhttp.util.JdcrHttpLog
import com.jdcr.jdcrhttp.util.JdcrHttpUtils
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.RedirectResponseException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.plugins.websocket.WebSocketException
import io.ktor.client.statement.HttpResponse
import io.ktor.network.sockets.SocketTimeoutException
import io.ktor.serialization.ContentConvertException
import io.ktor.util.network.UnresolvedAddressException
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.serialization.SerializationException
import kotlin.coroutines.cancellation.CancellationException

@PublishedApi
internal fun String.safePath() = JdcrHttpUtils.sanitizeLogMessage(this, "****")

suspend inline fun <reified T> handleRequestResult(
    pathOrUrl: String,
    execute: () -> T
): JdcrHttpResult<T> {
    return try {
        JdcrHttpResult.Success(execute())
    } catch (e: CancellationException) {
        JdcrHttpLog.i("协程网络请求取消:${pathOrUrl.safePath()}")
        throw e
    } catch (e: Exception) {
        getRequestFailResult(pathOrUrl, e)
    }
}

@PublishedApi
internal suspend fun readErrorBodyOrFallback(
    response: HttpResponse,
    fallback: String,
): String {
    return try {
        response.body<String>()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        fallback
    }
}

suspend inline fun <reified T> getRequestFailResult(
    pathOrUrl: String,
    e: Exception
): JdcrHttpResult<T> {
    return when (e) {

        is CancellationException -> throw e

        is ClientRequestException -> {
            // 4xx 错误（400, 401, 403, 404 等）
            // 前提：expectSuccess = true
            val msg = readErrorBodyOrFallback(response = e.response, fallback = e.message)
            val status = e.response.status
            JdcrHttpLog.w("客户端错误:${pathOrUrl.safePath()},$status,$msg", e)
            JdcrHttpResult.Failure.HttpError(e.response.status.value, msg)
        }

        is ServerResponseException -> {
            // 5xx 错误（500, 502, 503 等）
            // 前提：expectSuccess = true
            val msg = readErrorBodyOrFallback(response = e.response, fallback = e.message)
            val status = e.response.status
            JdcrHttpLog.w("服务端错误:${pathOrUrl.safePath()},$status,$msg", e)
            JdcrHttpResult.Failure.HttpError(e.response.status.value, msg)
        }

        is UnresolvedAddressException -> {
            // 无法访问
            JdcrHttpLog.w("接口地址异常:${pathOrUrl.safePath()}", e)
            JdcrHttpResult.Failure.ConnectError(e)
        }

        is HttpRequestTimeoutException -> {
            // 请求超时
            JdcrHttpLog.w("请求超时:${pathOrUrl.safePath()}", e)
            JdcrHttpResult.Failure.LocalError.Timeout(e)
        }

        is ConnectTimeoutException -> {
            // 连接超时
            JdcrHttpLog.w("连接超时:${pathOrUrl.safePath()}", e)
            JdcrHttpResult.Failure.LocalError.Timeout(e)
        }

        is SocketTimeoutException -> {
            // Socket超时
            JdcrHttpLog.w("Socket超时:${pathOrUrl.safePath()}", e)
            JdcrHttpResult.Failure.LocalError.Timeout(e)
        }

        is IOException -> {
            // 网络异常（断网、DNS 解析失败等）
            JdcrHttpLog.w("网络异常:${pathOrUrl.safePath()}", e)
            JdcrHttpResult.Failure.LocalError.Network(e)
        }

        is SerializationException, is ContentConvertException -> {
            // JSON 解析失败
            JdcrHttpLog.w("Json解析失败:${pathOrUrl.safePath()}", e)
            JdcrHttpResult.Failure.LocalError.Serialization(e)
        }

        is RedirectResponseException -> {
            val msg = readErrorBodyOrFallback(response = e.response, fallback = e.message)
            val status = e.response.status
            JdcrHttpLog.w("重定向响应异常:${pathOrUrl.safePath()},$status,$msg", e)
            JdcrHttpResult.Failure.HttpError(e.response.status.value, msg)
        }

        is NoTransformationFoundException -> {
            // 可能是wss使用了https
            JdcrHttpLog.w("可能是http协议错了:${pathOrUrl.safePath()}", e)
            JdcrHttpResult.Failure.ConnectError(e)
        }

        is ClosedSendChannelException,
        is ClosedReceiveChannelException -> {
            JdcrHttpLog.w("WebSocket通道已关闭:${pathOrUrl.safePath()}", e) //针对ws
            JdcrHttpResult.Failure.LocalError.WsClosed(e)
        }

        is WebSocketException -> {
            // WebSocket 独有的异常（比如握手失败 400/401/403 等）
            JdcrHttpLog.w("WebSocket异常:${pathOrUrl.safePath()}", e)
            JdcrHttpResult.Failure.ConnectError(e)
        }

        else -> {
            // 兜底捕获
            JdcrHttpLog.w("未知异常:${pathOrUrl.safePath()}", e)
            JdcrHttpResult.Failure.LocalError.Unknown(e)
        }

    }
}