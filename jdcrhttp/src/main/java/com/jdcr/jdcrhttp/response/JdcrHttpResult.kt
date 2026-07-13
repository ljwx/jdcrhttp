package com.jdcr.jdcrhttp.response

sealed interface JdcrHttpResult<out T> {

    data class Success<T>(val data: T) : JdcrHttpResult<T>

    sealed interface Failure : JdcrHttpResult<Nothing> {
        open val message: String

        data class HttpError(val code: Int, override val message: String) : Failure
        data class BusinessError(val code: Int, override val message: String, val body: String?) :
            Failure

        data class ConnectError(
            val throwable: Throwable,
            override val message: String = throwable.message ?: ""
        ) : Failure

        sealed interface LocalError : Failure {
            val throwable: Throwable

            data class Network(
                override val throwable: Throwable,
                override val message: String = throwable.message ?: ""
            ) : LocalError

            data class Timeout(
                override val throwable: Throwable,
                override val message: String = throwable.message ?: ""
            ) : LocalError

            data class Serialization(
                override val throwable: Throwable,
                override val message: String = throwable.message ?: ""
            ) : LocalError

            data class WsClosed(
                override val throwable: Throwable,
                override val message: String = throwable.message ?: ""
            ) : LocalError

            data class Unknown(
                override val throwable: Throwable,
                override val message: String = throwable.message ?: ""
            ) : LocalError
        }
    }

}

inline val JdcrHttpResult<*>.isSuccess: Boolean
    get() = this is JdcrHttpResult.Success

fun <T> JdcrHttpResult<T>.getOrNull(): T? =
    (this as? JdcrHttpResult.Success)?.data

fun <T> JdcrHttpResult<T>.exceptionOrNull(): Throwable? =
    (this as? JdcrHttpResult.Failure.LocalError)?.throwable
        ?: (this as? JdcrHttpResult.Failure.ConnectError)?.throwable

fun <T> JdcrHttpResult<T>.failureMessageOrNull(): String? =
    (this as? JdcrHttpResult.Failure)?.message

fun <T> JdcrHttpResult<T>.getOrDefault(default: T): T =
    (this as? JdcrHttpResult.Success)?.data ?: default

fun <T> JdcrHttpResult<T>.getOrElse(action: (JdcrHttpResult.Failure?) -> T): T =
    (this as? JdcrHttpResult.Success)?.data ?: action(this as? JdcrHttpResult.Failure)

fun <T> JdcrHttpResult<T>.getOrThrow(): T =
    (this as? JdcrHttpResult.Success)?.data ?: throw IllegalStateException("请求没有成功:$this")

inline fun <T, R> JdcrHttpResult<T>.map(transform: (T) -> R): JdcrHttpResult<R> =
    when (this) {
        is JdcrHttpResult.Success -> JdcrHttpResult.Success(transform(data))
        is JdcrHttpResult.Failure -> this
    }

inline fun <T> JdcrHttpResult<T>.onSuccess(action: (T) -> Unit): JdcrHttpResult<T> {
    if (this is JdcrHttpResult.Success) action(data)
    return this
}

inline fun <T> JdcrHttpResult<T>.onFailure(action: (JdcrHttpResult.Failure) -> Unit): JdcrHttpResult<T> {
    if (this is JdcrHttpResult.Failure) action(this)
    return this
}

inline fun <T, R> JdcrHttpResult<T>.fold(
    onSuccess: (T) -> R,
    onFailure: (JdcrHttpResult.Failure) -> R,
): R = when (this) {
    is JdcrHttpResult.Success -> onSuccess(data)
    is JdcrHttpResult.Failure -> onFailure(this)
}