package com.jdcr.jdcrhttp.response

import com.jdcr.jdcrhttp.util.JdcrHttpLog
import io.ktor.client.call.body
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.utils.io.errors.IOException
import kotlinx.serialization.SerializationException
import kotlin.coroutines.cancellation.CancellationException

suspend inline fun <reified T> handleRequestResult(
    pathOrUrl: String,
    execute: () -> T
): JdcrHttpResult<T> {
    return try {
        JdcrHttpResult.Success(execute())
    } catch (e: ClientRequestException) {
        // 4xx 错误（400, 401, 403, 404 等）
        // 前提：expectSuccess = true
        val msg = runCatching { e.response.body<String>() }.getOrElse { e.message }
        JdcrHttpLog.w("客户端错误: $pathOrUrl ${e.response.status},$msg")
        return JdcrHttpResult.Failure.HttpError(e.response.status.value, msg)
    } catch (e: ServerResponseException) {
        // 5xx 错误（500, 502, 503 等）
        // 前提：expectSuccess = true
        val msg = runCatching { e.response.body<String>() }.getOrElse { e.message }
        JdcrHttpLog.w("服务端错误: $pathOrUrl ${e.response.status},$msg")
        return JdcrHttpResult.Failure.HttpError(e.response.status.value, msg)
    } catch (e: HttpRequestTimeoutException) {
        // 请求超时
        JdcrHttpLog.w("请求超时: $pathOrUrl")
        return JdcrHttpResult.Failure.LocalError.Timeout(e)
    } catch (e: ConnectTimeoutException) {
        // 连接超时
        JdcrHttpLog.w("连接超时: $pathOrUrl")
        return JdcrHttpResult.Failure.LocalError.Timeout(e)
    } catch (e: IOException) {
        // 网络异常（断网、DNS 解析失败等）
        JdcrHttpLog.w("网络异常: $pathOrUrl ${e.message}")
        return JdcrHttpResult.Failure.LocalError.Network(e)
    } catch (e: SerializationException) {
        // JSON 解析失败
        JdcrHttpLog.w("解析失败: $pathOrUrl")
        return JdcrHttpResult.Failure.LocalError.Serialization(e)
    } catch (e: CancellationException) {
        // 协程取消（页面销毁等）
        JdcrHttpLog.w("协程取消: $pathOrUrl")
        throw e
    } catch (e: Exception) {
        // 兜底捕获
        JdcrHttpLog.w("未知异常: $pathOrUrl ${e.message}")
        return JdcrHttpResult.Failure.LocalError.Unknown(e)
    }
}