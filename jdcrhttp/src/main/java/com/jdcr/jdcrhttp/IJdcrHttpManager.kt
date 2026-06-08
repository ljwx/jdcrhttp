package com.jdcr.jdcrhttp

fun resolveHttpUrl(baseUrl: String, pathOrUrl: String): String {
    val trimmed = pathOrUrl.trim()
    val isAbsolute = listOf("http://", "https://", "ws://", "wss://")
        .any { trimmed.startsWith(it, ignoreCase = true) }
    if (isAbsolute) return trimmed
    val base = baseUrl.trimEnd('/')
    if (base.isEmpty()) return trimmed.trimStart('/')
    return "$base/${trimmed.trimStart('/')}"
}

interface IJdcrHttpManager {
    val baseUrl: String
    fun resolveUrl(pathOrUrl: String): String = resolveHttpUrl(baseUrl, pathOrUrl)
}