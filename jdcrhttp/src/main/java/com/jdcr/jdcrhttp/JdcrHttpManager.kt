package com.jdcr.jdcrhttp

import com.jdcr.jdcrhttp.client.JdcrHttpClientFactory
import com.jdcr.jdcrhttp.client.JdcrHttpLogLevel
import com.jdcr.jdcrhttp.client.toLevel
import com.jdcr.jdcrhttp.response.JdcrHttpResult
import com.jdcr.jdcrhttp.response.JdcrSSEEvent
import com.jdcr.jdcrhttp.response.JdcrSseConnection
import com.jdcr.jdcrhttp.response.asSseEventsResult
import com.jdcr.jdcrhttp.response.getRequestFailResult
import com.jdcr.jdcrhttp.response.handleRequestResult
import com.jdcr.jdcrhttp.util.JdcrHttpLog
import io.ktor.client.HttpClient
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class JdcrHttpManager(
    @PublishedApi override var client: HttpClient,
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
            check(manager == null) {
                "JdcrHttpManager已初始化, 如需切换请先 destroyClient"
            }
            return manager ?: synchronized(this) {
                manager ?: JdcrHttpManager(
                    client ?: JdcrHttpClientFactory.getDefaultHttp(),
                    baseUrl
                ).also {
                    JdcrHttpLog.i("JdcrHttpManager初始化成功")
                    manager = it
                }
            }
        }

        fun isInitialized(): Boolean {
            return manager != null
        }

        fun instance(): JdcrHttpManager {
            return requireNotNull(manager) {
                "请先初始化,JdcrHttpManager.initInstance()"
            }
        }
    }

    fun changeLogLevel(level: JdcrHttpLogLevel) {
        val logging = client.plugin(Logging)
        logging.level = level.toLevel()
        JdcrHttpLog.i("修改日志等级为: $level")
    }

    @PublishedApi
    internal suspend fun openSSEConnection(
        pathOrUrl: String,
        createStatement: suspend () -> HttpStatement,
    ): JdcrHttpResult<JdcrSseConnection> =
        handleRequestResult(pathOrUrl) {

            val events = Channel<JdcrHttpResult<JdcrSSEEvent>>(Channel.BUFFERED)
            val connected = CompletableDeferred<Unit>()
            var connection: JdcrSseConnection? = null

            val readJob = sseScope.launch {
                try {
                    createStatement().execute { response ->
                        connected.complete(Unit)
                        response.bodyAsChannel()
                            .asSseEventsResult(pathOrUrl)
                            .collect { events.send(it) }
                    }
                    events.close()
                } catch (e: CancellationException) {
                    if (!connected.isCompleted) {
                        connected.completeExceptionally(e)
                    }
                    events.close(e)
                    return@launch
                } catch (e: Exception) {
                    if (!connected.isCompleted) {
                        connected.completeExceptionally(e)
                        events.close(e)
                        return@launch
                    }
                    events.trySend(getRequestFailResult(pathOrUrl, e))
                    events.close()
                }
            }

            connection = JdcrSseConnection(
                pathOrUrl,
                events.receiveAsFlow().onCompletion {
                    readJob.cancel()
                },
                readJob
            )

            try {
                connected.await()
            } catch (e: Exception) {
                readJob.cancel()
                throw e
            }

            connection
        }

    suspend inline fun getSSEConnection(
        pathOrUrl: String,
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ): JdcrHttpResult<JdcrSseConnection> =
        openSSEConnection(pathOrUrl) {
            client.prepareGet {
                sseRequestConfig(pathOrUrl)
                block()
            }
        }

    suspend inline fun postSSEConnection(
        pathOrUrl: String,
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ): JdcrHttpResult<JdcrSseConnection> =
        openSSEConnection(pathOrUrl) {
            client.preparePost {
                sseRequestConfig(pathOrUrl)
                block()
            }
        }

}
