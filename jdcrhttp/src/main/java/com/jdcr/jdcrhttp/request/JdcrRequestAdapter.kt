package com.jdcr.jdcrhttp.request

import com.jdcr.jdcrhttp.auth.currentApiRequestAuthToken
import com.jdcr.jdcrhttp.auth.excludeAuthToken
import com.jdcr.jdcrhttp.auth.needAuthToken
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

internal fun HttpRequestBuilder.applyAuthMode(
    mode: JdcrRequestAuthMode,
) {
    when (mode) {
        JdcrRequestAuthMode.DEFAULT -> Unit

        JdcrRequestAuthMode.REQUIRED -> {
            needAuthToken()
        }

        JdcrRequestAuthMode.EXCLUDED -> {
            excludeAuthToken()
        }

        JdcrRequestAuthMode.MANUAL -> {
            // 本次请求自己设置 Authorization，
            // 阻止全局认证逻辑再次添加 Token。
            currentApiRequestAuthToken()
        }
    }
}

@PublishedApi
internal fun HttpRequestBuilder.applyJdcrRequest(
    options: JdcrRequestOptions,
) {
    options.headers.forEach { (name, values) ->
        // 确保单次请求配置能够覆盖 defaultRequest/SSE 默认 Header
        headers.remove(name)

        values.forEach { value ->
            headers.append(name, value)
        }
    }

    options.parameters.forEach { (name, value) ->
        url.parameters.append(name, value)
    }

    options.timeout?.let { value ->
        timeout {
            value.connectTimeoutMillis?.let {
                connectTimeoutMillis = it
            }
            value.socketTimeoutMillis?.let {
                socketTimeoutMillis = it
            }
            value.requestTimeoutMillis?.let {
                requestTimeoutMillis = it
            }
        }
    }

    when (val body = options.body) {
        JdcrRequestBody.Empty -> Unit

        is JdcrRequestBody.Text -> {
            body.contentType?.let {
                contentType(ContentType.parse(it))
            }
            setBody(body.value)
        }

        is JdcrRequestBody.Bytes -> {
            body.contentType?.let {
                contentType(ContentType.parse(it))
            }
            setBody(body.value)
        }
    }

    applyAuthMode(options.authMode)
}