package com.jdcr.jdcrhttp

import com.jdcr.jdcrhttp.client.JdcrHttpClientFactory
import com.jdcr.jdcrhttp.response.JdcrHttpResult
import com.jdcr.jdcrhttp.response.JdcrSSEEvent
import com.jdcr.jdcrhttp.response.asSseEventsResult
import com.jdcr.jdcrhttp.response.getRequestFailResult
import com.jdcr.jdcrhttp.response.handleRequestResult
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class JdcrHttpManager(
    override var client: HttpClient,
    override val baseUrl: String,
) : JdcrHttpCore(client, baseUrl), IJdcrHttpManager {

    companion object {
        @Volatile
        @PublishedApi
        internal var manager: JdcrHttpManager? = null
        fun initInstance(
            baseUrl: String,
            client: HttpClient? = null
        ): JdcrHttpManager {
            manager?.let { return it }
            return synchronized(this) {
                manager ?: JdcrHttpManager(
                    client ?: JdcrHttpClientFactory.getDefaultHttp(),
                    baseUrl
                ).also { manager = it }
            }
        }

        fun instance(): JdcrHttpManager {
            return requireNotNull(manager) {
                "请先初始化,JdcrHttpManager.initInstance()"
            }
        }
    }

    private suspend fun openSSEFlow(
        pathOrUrl: String,
        createStatement: suspend () -> HttpStatement,
    ): JdcrHttpResult<Flow<JdcrHttpResult<JdcrSSEEvent>>> =
        handleRequestResult(pathOrUrl) {
            val events = Channel<JdcrHttpResult<JdcrSSEEvent>>(Channel.BUFFERED)
            val connected = CompletableDeferred<Unit>()

            val readJob = sseScope.launch {
                try {
                    createStatement().execute { response ->
                        connected.complete(Unit)

                        response.bodyAsChannel()
                            .asSseEventsResult(pathOrUrl)
                            .collect { events.send(it) }
                    }

                    events.close()
                } catch (e: Exception) {
                    if (!connected.isCompleted) {
                        connected.completeExceptionally(e)
                        events.close(e)
                        return@launch
                    }

                    if (e is CancellationException) {
                        events.close(e)
                        return@launch
                    }

                    events.trySend(getRequestFailResult(pathOrUrl, e))
                    events.close()
                }
            }

            try {
                connected.await()
            } catch (e: Exception) {
                readJob.cancel()
                throw e
            }

            events.receiveAsFlow()
                .onCompletion {
                    readJob.cancel()
                }
        }

    suspend fun getSSEFlow(
        pathOrUrl: String,
        block: HttpRequestBuilder.() -> Unit = {},
    ): JdcrHttpResult<Flow<JdcrHttpResult<JdcrSSEEvent>>> =
        openSSEFlow(pathOrUrl) {
            client.prepareGet {
                sseRequestConfig(pathOrUrl)
                block()
            }
        }

    suspend fun postSSEFlow(
        pathOrUrl: String,
        block: HttpRequestBuilder.() -> Unit = {},
    ): JdcrHttpResult<Flow<JdcrHttpResult<JdcrSSEEvent>>> =
        openSSEFlow(pathOrUrl) {
            client.preparePost {
                sseRequestConfig(pathOrUrl)
                block()
            }
        }

}
