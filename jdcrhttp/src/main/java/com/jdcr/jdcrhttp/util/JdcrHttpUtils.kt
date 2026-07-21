package com.jdcr.jdcrhttp.util

import android.content.Context
import android.net.ConnectivityManager
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.ProxyConfig
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol

object JdcrHttpUtils {

    private val sensitiveHeaderRegex =
        Regex("(?im)^(Authorization|Proxy-Authorization|Cookie|Set-Cookie):\\s*(.+)$")
    private val sensitiveJsonRegex =
        Regex("(?i)\"(access_token|refresh_token|token|password|passwd|pwd|authorization)\"\\s*:\\s*\"([^\"]*)\"")
    private val sensitiveFormRegex =
        Regex("(?i)(access_token|refresh_token|token|password|passwd|pwd)=([^&\\s]+)")
    private val bearerTokenRegex = Regex("(?i)Bearer\\s+[A-Za-z0-9\\-._~+/]+=*")

    fun sanitizeLogMessage(message: String, redactedText: String): String {
        var sanitized = message
        sanitized = sensitiveHeaderRegex.replace(sanitized) { match ->
            "${match.groupValues[1]}: $redactedText"
        }
        sanitized = sensitiveJsonRegex.replace(sanitized) { match ->
            "\"${match.groupValues[1]}\":\"$redactedText\""
        }
        sanitized = sensitiveFormRegex.replace(sanitized) { match ->
            "${match.groupValues[1]}=$redactedText"
        }
        sanitized = bearerTokenRegex.replace(sanitized, "Bearer $redactedText")
        return sanitized
    }

    fun getSystemProxy(context: Context): ProxyConfig? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        manager.defaultProxy?.let { proxyInfo ->
            val hostProxy = proxyInfo.host
            val portProxy = proxyInfo.port
            if (!hostProxy.isNullOrBlank() && portProxy > 0) {
                val proxyUrl = URLBuilder().apply {
                    protocol = URLProtocol.HTTP
                    host = proxyInfo.host
                    port = proxyInfo.port
                }.build()
                return ProxyBuilder.http(proxyUrl)
            }
        }
        return null
    }

}