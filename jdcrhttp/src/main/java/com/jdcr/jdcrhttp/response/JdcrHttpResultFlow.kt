package com.jdcr.jdcrhttp.response

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

fun <T> Flow<JdcrHttpResult<T>>.unwrap(): Flow<T> = map { result -> result.getOrThrow() }