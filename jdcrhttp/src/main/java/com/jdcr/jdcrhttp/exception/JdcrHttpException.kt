package com.jdcr.jdcrhttp.exception

import com.jdcr.jdcrhttp.response.JdcrHttpResult

class JdcrHttpException(val failure: JdcrHttpResult.Failure) : RuntimeException(
    failure.displayMessage(),
    failure.causeOrNull(),
)

fun JdcrHttpResult.Failure.causeOrNull(): Throwable? =
    when (this) {
        is JdcrHttpResult.Failure.ConnectError ->
            throwable

        is JdcrHttpResult.Failure.LocalError ->
            throwable

        else -> null
    }

fun JdcrHttpResult.Failure.displayMessage(): String =
    when (this) {
        is JdcrHttpResult.Failure.HttpError ->
            "HTTP $code: $message"

        is JdcrHttpResult.Failure.BusinessError ->
            "Business $code: $message"

        else -> message
    }

fun JdcrHttpResult.Failure.toException(): JdcrHttpException = JdcrHttpException(this)