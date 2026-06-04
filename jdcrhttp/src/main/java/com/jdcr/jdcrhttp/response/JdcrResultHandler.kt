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
        JdcrHttpLog.w("客户端错误: $pathOrUrl ${e.response.status}")
        return JdcrHttpResult.ServerFail(e.response.status.value, e.response.body())
    } catch (e: ServerResponseException) {
        // 5xx 错误（500, 502, 503 等）
        // 前提：expectSuccess = true
        JdcrHttpLog.w("服务端错误: $pathOrUrl ${e.response.status}")
        return JdcrHttpResult.ServerFail(e.response.status.value, e.response.body())
    } catch (e: HttpRequestTimeoutException) {
        // 请求超时
        JdcrHttpLog.w("请求超时: $pathOrUrl")
        return JdcrHttpResult.Exception.TimeoutException(e)
    } catch (e: ConnectTimeoutException) {
        // 连接超时
        JdcrHttpLog.w("连接超时: $pathOrUrl")
        return JdcrHttpResult.Exception.TimeoutException(e)
    } catch (e: IOException) {
        // 网络异常（断网、DNS 解析失败等）
        JdcrHttpLog.w("网络异常: $pathOrUrl ${e.message}")
        return JdcrHttpResult.Exception.NetworkException(e)
    } catch (e: SerializationException) {
        // JSON 解析失败
        JdcrHttpLog.w("解析失败: $pathOrUrl")
        return JdcrHttpResult.Exception.JsonException(e)
    } catch (e: CancellationException) {
        // 协程取消（页面销毁等）
        JdcrHttpLog.w("协程取消: $pathOrUrl")
        return JdcrHttpResult.Exception.CancelException(e)
    } catch (e: Exception) {
        // 兜底捕获
        JdcrHttpLog.w("未知异常: $pathOrUrl ${e.message}")
        return JdcrHttpResult.Exception.UnknownException(e)
    }
}