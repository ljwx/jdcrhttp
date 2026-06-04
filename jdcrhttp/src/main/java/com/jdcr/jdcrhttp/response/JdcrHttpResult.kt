package com.jdcr.jdcrhttp.response

sealed class JdcrHttpResult<out T> {

    data class Success<T>(val data: T) : JdcrHttpResult<T>()
    data class ServerFail(val code: Int, val message: String) : JdcrHttpResult<Nothing>()
    sealed class Exception() : JdcrHttpResult<Nothing>() {
        data class NetworkException(val throwable: Throwable) : Exception()
        data class TimeoutException(val throwable: Throwable) : Exception()
        data class JsonException(val throwable: Throwable) : Exception()
        data class CancelException(val throwable: Throwable) : Exception()
        data class UnknownException(val throwable: Throwable) : Exception()
    }

}