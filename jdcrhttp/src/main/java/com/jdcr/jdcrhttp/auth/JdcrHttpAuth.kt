package com.jdcr.jdcrhttp.auth

import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.util.AttributeKey
import java.util.concurrent.atomic.AtomicReference

internal val NeedAuthTokenKey = AttributeKey<Boolean>("needAuthToken")
internal val CurrentRequireAuthTokenKey = AttributeKey<Boolean>("currentRequireAuthToken")

fun HttpRequestBuilder.currentRequestAuthToken() {
    attributes.put(CurrentRequireAuthTokenKey, true)
}

fun HttpRequestBuilder.needAuthToken() {
    attributes.put(NeedAuthTokenKey, true)
}

fun HttpRequestBuilder.excludeAuthToken() {
    attributes.put(NeedAuthTokenKey, false)
}

internal fun HttpRequestBuilder.isCurrentRequestAuthToken(): Boolean {
    return this.attributes.getOrNull(CurrentRequireAuthTokenKey) == true
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

}