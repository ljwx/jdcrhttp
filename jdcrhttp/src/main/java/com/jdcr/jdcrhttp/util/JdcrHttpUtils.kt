package com.jdcr.jdcrhttp.util

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

}