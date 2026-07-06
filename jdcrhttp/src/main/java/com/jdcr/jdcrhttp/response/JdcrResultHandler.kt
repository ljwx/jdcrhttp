package com.jdcr.jdcrhttp.response

import com.jdcr.jdcrhttp.util.JdcrHttpLog
import io.ktor.client.call.body
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.plugins.websocket.WebSocketException
import io.ktor.network.sockets.SocketTimeoutException
import io.ktor.util.network.UnresolvedAddressException
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.SerializationException
import kotlin.coroutines.cancellation.CancellationException

suspend inline fun <reified T> handleRequestResult(
    pathOrUrl: String,
    execute: () -> T
): JdcrHttpResult<T> {
    return try {
        JdcrHttpResult.Success(execute())
    } catch (e: Exception) {
        return getRequestFailResult(pathOrUrl, e)
    }
}

suspend inline fun <reified T> getRequestFailResult(
    pathOrUrl: String,
    e: Exception
): JdcrHttpResult<T> {
    return when (e) {
        is ClientRequestException -> {
            // 4xx 错误（400, 401, 403, 404 等）
            // 前提：expectSuccess = true
            val msg = runCatching { e.response.body<String>() }.getOrElse { e.message }
            JdcrHttpLog.wd("客户端错误", e, "$pathOrUrl ${e.response.status},$msg")
            JdcrHttpResult.Failure.HttpError(e.response.status.value, msg)
        }

        is ServerResponseException -> {
            // 5xx 错误（500, 502, 503 等）
            // 前提：expectSuccess = true
            val msg = runCatching { e.response.body<String>() }.getOrElse { e.message }
            JdcrHttpLog.wd("服务端错误", e, "$pathOrUrl ${e.response.status},$msg")
            JdcrHttpResult.Failure.HttpError(e.response.status.value, msg)
        }

        is UnresolvedAddressException -> {
            // 无法访问
            JdcrHttpLog.wd("接口地址异常", e, "$pathOrUrl")
            JdcrHttpResult.Failure.ConnectError(e)
        }

        is HttpRequestTimeoutException -> {
            // 请求超时
            JdcrHttpLog.wd("请求超时", e, "$pathOrUrl")
            JdcrHttpResult.Failure.LocalError.Timeout(e)
        }

        is ConnectTimeoutException -> {
            // 连接超时
            JdcrHttpLog.wd("连接超时", e, "$pathOrUrl")
            JdcrHttpResult.Failure.LocalError.Timeout(e)
        }

        is SocketTimeoutException -> {
            // Socket超时
            JdcrHttpLog.wd("Socket超时", e, "$pathOrUrl")
            JdcrHttpResult.Failure.LocalError.Timeout(e)
        }

        is IOException -> {
            // 网络异常（断网、DNS 解析失败等）
            JdcrHttpLog.wd("网络异常", e, "$pathOrUrl ${e.message}")
            JdcrHttpResult.Failure.LocalError.Network(e)
        }

        is SerializationException -> {
            // JSON 解析失败
            JdcrHttpLog.wd("Json解析失败", e, "$pathOrUrl")
            JdcrHttpResult.Failure.LocalError.Serialization(e)
        }

        is CancellationException -> {
            // 协程取消（页面销毁等）
            JdcrHttpLog.wd("协程取消", e, "$pathOrUrl")
            throw e
        }

        is ClosedReceiveChannelException -> {
            JdcrHttpLog.wd("WebSocket连接已正常断开", e, " $pathOrUrl") //针对ws
            JdcrHttpResult.Failure.LocalError.WsClosed(e)
        }

        is WebSocketException -> {
            // WebSocket 独有的异常（比如握手失败 400/401/403 等）
            JdcrHttpLog.wd("WebSocket异常", e, "$pathOrUrl ${e.message}")
            // 它是属于协议/业务错误，你可以提取里面的状态码（如果有），或者直接归类为 HttpError / NetworkError
            JdcrHttpResult.Failure.HttpError(400, e.message ?: "WebSocket握手失败")
        }

        else -> {
            // 兜底捕获
            JdcrHttpLog.wd("未知异常", e, "$pathOrUrl ${e.message}")
            JdcrHttpResult.Failure.LocalError.Unknown(e)
        }

    }
}