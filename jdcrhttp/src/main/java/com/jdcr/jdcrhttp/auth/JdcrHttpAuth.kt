package com.jdcr.jdcrhttp.auth

import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.util.AttributeKey
import java.util.concurrent.atomic.AtomicReference

internal val NeedAuthTokenKey = AttributeKey<Boolean>("needAuthToken")
internal val CurrentApiRequireAuthTokenKey = AttributeKey<Boolean>("currentRequireAuthToken")

fun HttpRequestBuilder.currentApiRequestAuthToken() {
    attributes.put(CurrentApiRequireAuthTokenKey, true)
}

fun HttpRequestBuilder.needAuthToken() {
    attributes.put(NeedAuthTokenKey, true)
}

fun HttpRequestBuilder.excludeAuthToken() {
    attributes.put(NeedAuthTokenKey, false)
}

internal fun HttpRequestBuilder.isCurrentApiRequestAuthToken(): Boolean {
    return this.attributes.getOrNull(CurrentApiRequireAuthTokenKey) == true
}

internal fun HttpRequestBuilder.isNeedToken(): Boolean {
    return this.attributes.getOrNull(NeedAuthTokenKey) == true
}

internal fun HttpRequestBuilder.isExcludeToken(): Boolean {
    return this.attributes.getOrNull(NeedAuthTokenKey) == false
}

object JdcrHttpAuthUtils {

    private var token: AtomicReference<String?> = AtomicReference(null)

    private var refreshToken: AtomicReference<String?> = AtomicReference(null)

    fun setGlobalToken(token: String) {
        this.token.set(token)
    }

    fun getGlobalToken(): String? {
        return token.get()
    }

    fun setGlobalRefreshToken(refresh: String?) {
        this.refreshToken.set(refresh)
    }

    fun getGlobalRefreshToken(): String? {
        return refreshToken.get()
    }

    fun getHeaderToken(providerToken: String?): String? {
        return providerToken ?: token.get()
    }

    fun getHeaderRefreshToken(providerRefreshToken: String?): String? {
        return providerRefreshToken ?: refreshToken.get()
    }

    fun getBearerTokens(token: String?, refresh: String?): BearerTokens? {
        val headerToken = getHeaderToken(token)
        val headerRefresh = getHeaderRefreshToken(refresh)
        return if (headerToken == null) {
            null
        } else {
            BearerTokens(headerToken, headerRefresh ?: "")
        }
    }

    internal fun handlePluginToken(
        request: HttpRequestBuilder,
        allApiSendToken: Boolean,
    ): Boolean {
        var shouldSend = if (allApiSendToken) {
            !request.isExcludeToken()
        } else {
            request.isNeedToken()
        }
        if (!shouldSend) {
            request.attributes.put(Auth.AuthCircuitBreaker, Unit)
        }
        if (request.isCurrentApiRequestAuthToken()) {
            request.attributes.put(Auth.AuthCircuitBreaker, Unit)
            shouldSend = false
        }
        return shouldSend
    }

    internal suspend fun handleCustomToken(
        request: HttpRequestBuilder,
        allApiSendToken: Boolean,
        key: String,
        tokenProvider: (suspend () -> String?)? = null
    ) {
        if (request.isCurrentApiRequestAuthToken()) {
            return
        }
        if (allApiSendToken) {
            if (!request.isExcludeToken()) {
                getHeaderToken(tokenProvider?.invoke())?.let { request.headers[key] = it }
            }
        } else {
            if (request.isNeedToken()) {
                getHeaderToken(tokenProvider?.invoke())?.let { request.headers[key] = it }
            }
        }
    }

}