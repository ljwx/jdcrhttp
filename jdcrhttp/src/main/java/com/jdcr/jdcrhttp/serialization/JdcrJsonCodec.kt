package com.jdcr.jdcrhttp.serialization

import com.jdcr.jdcrlog.JdcrLog
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer


object JdcrJsonCodec {

    @PublishedApi
    internal val API_JSON = Json {
        /**
         * 忽略数据类中未声明的 JSON 字段。
         *
         * 后端新增字段时，旧客户端不会因为未知字段解析失败。
         * 类似 Gson 默认忽略未知字段的行为。
         */
        ignoreUnknownKeys = true

        /**
         * 编码时输出等于默认值的字段。
         *
         * 例如：
         * val success: Boolean = false
         *
         * 仍然会输出：
         * {"success":false}
         *
         * 避免因为字段等于默认值就不传给后端。
         */
        encodeDefaults = true

        /**
         * 编码时不输出值为 null 的字段。
         *
         * 例如：
         * val message: String? = null
         *
         * message 会从 JSON 中省略，类似 Gson 默认没有调用
         * serializeNulls() 时的行为。
         */
        explicitNulls = false

        /**
         * 遇到非法输入不强制回退到默认值，直接抛异常。
         *
         * 例如 JSON 里非空字段收到 null、未知枚举值等情况，
         * 解析会失败，避免静默掩盖后端数据问题。
         */
        coerceInputValues = false

        /**
         * 不接受非标准 JSON 语法。
         *
         * 字符串和字段名必须正确使用双引号。
         * 只有确定旧接口返回非法 JSON 时才改成 true。
         */
        isLenient = false

        /**
         * 不允许 NaN、Infinity、-Infinity。
         *
         * 这些值不是标准 JSON，后端、网关或 JavaScript
         * 可能无法正确解析。
         */
        allowSpecialFloatingPointValues = false

        /**
         * 正式环境不格式化 JSON。
         *
         * 避免额外的空格和换行。
         */
        prettyPrint = false

    }

    inline fun <reified T> toJson(data: T): Result<String> =
        toJson(data, API_JSON.serializersModule.serializer())

    inline fun <reified T> fromJson(json: String): Result<T> =
        fromJson(json, API_JSON.serializersModule.serializer())

    fun <T> toJson(data: T, serializer: KSerializer<T>): Result<String> {
        return try {
            Result.success(API_JSON.encodeToString(serializer, data))
        } catch (e: Throwable) {
            JdcrLog.w("JSON序列化失败", e)
            Result.failure(e)
        }
    }

    fun <T> fromJson(json: String, serializer: KSerializer<T>): Result<T> {
        return try {
            Result.success(API_JSON.decodeFromString(serializer, json))
        } catch (e: Throwable) {
            JdcrLog.w("JSON解析失败", e)
            Result.failure(e)
        }
    }

}