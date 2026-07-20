# JdcrHttp 流式问答接入示例（SSE + WebSocket）

本文用伪代码说明一种常见的流式问答架构：

- SSE 接收大模型增量文本；
- WebSocket 接收音频事件；
- SSE 文本实时转发给 WebSocket TTS 服务；
- 音频数据写入流式播放器 Buffer；
- 对外通过 `Flow<State>` 通知连接、首帧、失败和播放完成状态。

示例来源于 Tools SDK 的 `AIChatStreamManager`，省略了具体业务 DTO、日志和播放器实现。代码用于说明接入方式，不保证复制后可以直接编译。

---

## 1. 整体流程

```text
用户问题
   │
   ├── 并发建立 SSE 问答连接
   │       └── 持续收到文本片段
   │
   └── 并发建立 WebSocket 音频连接
           ├── 发送 START
           ├── 接收 SSE 文本并发送 CONTENT
           ├── SSE 结束后发送 FINISH
           └── 接收音频块并写入播放器 Buffer

任意一侧失败
   └── 结束 Buffer、关闭连接、取消整轮任务、通知业务层
```

SSE 和 WebSocket 是同一轮任务的两个组成部分，生命周期必须绑定。不能只停止其中一侧，让另一侧继续等待。

---

## 2. 对外状态

```kotlin
sealed interface StreamState {
    data class ConnectResult(
        val answer: Result<Unit>,
        val audio: Result<Unit>
    ) : StreamState

    data class AnswerReady(val durationMs: Long) : StreamState
    data class AnswerCategory(val category: String) : StreamState
    data class AnswerFailed(val cause: Throwable) : StreamState

    data class AudioReady(val durationMs: Long) : StreamState
    data class AudioFailed(val cause: Throwable) : StreamState

    object PlayerCompleted : StreamState
    data class PlayerFailed(val cause: Throwable) : StreamState
}
```

建议一轮任务只暴露一个状态 Flow，并且只允许一个业务 collector。`Channel.receiveAsFlow()` 不是广播，多个 collector 会竞争同一个 Channel 中的数据。

---

## 3. 管理器伪代码

```kotlin
class StreamingQaManager {

    private val timeoutMs = 10_000L
    private val scope = SafeScope(Dispatchers.IO)

    private val http = JdcrHttpManager.instance()
    private val websocket = JdcrWebSocketManager.instance()

    private var taskJob: Job? = null
    private var stateChannel: Channel<StreamState>? = null
    private var player: StreamPlayer? = null

    fun start(question: String, token: String): Flow<StreamState> {
        // 新任务开始前，取消旧任务并停止旧音频。
        taskJob?.cancel()
        stateChannel?.cancel()
        player?.stop()

        val currentStates = Channel<StreamState>(Channel.BUFFERED)
        stateChannel = currentStates

        val currentJob = scope.launch {
            var sseConnection: JdcrSseConnection? = null
            var wsConnection: JdcrWsConnection? = null

            try {
                // 两个连接互不依赖，可以并发建立。
                val answerDeferred = async {
                    connectAnswer(question, token)
                }
                val audioDeferred = async {
                    connectAudio(token)
                }

                val answerResult = answerDeferred.await()
                val audioResult = audioDeferred.await()

                if (answerResult.isFailure || audioResult.isFailure) {
                    emitConnectFailure(answerResult, audioResult)

                    // 一侧成功、另一侧失败时，成功的一侧也要立即关闭。
                    answerResult.getOrNull()?.close()
                    audioResult.getOrNull()?.close()
                    return@launch
                }

                sseConnection = answerResult.getOrNull()!!
                wsConnection = audioResult.getOrNull()!!

                // 独立子协程持续接收 WS 音频。
                val audioJob = launch {
                    collectAudioEvents(wsConnection)
                }

                wsConnection.sendTextData(AudioCommand.Start)
                    .onFailure { failAudio(it.toThrowable()) }

                // 收集 SSE 文本，并实时转发给音频服务。
                collectAnswerEvents(sseConnection) { textEvent ->
                    val command = when (textEvent) {
                        is AnswerText.Content -> AudioCommand.Content(textEvent.text)
                        AnswerText.Finished -> AudioCommand.Finish
                    }

                    wsConnection.sendTextData(command)
                        .onFailure { failAudio(it.toThrowable()) }
                }

                // 结构化并发会等待 audioJob 完成。
                audioJob.join()
            } finally {
                // 无论成功、失败还是外部取消，都释放网络资源。
                runCatching { sseConnection?.close() }
                runCatching { wsConnection?.close() }
            }
        }

        taskJob = currentJob

        return currentStates
            .receiveAsFlow()
            .onCompletion {
                // 外部停止收集，即停止这一轮任务。
                currentJob.cancel()
                currentStates.cancel()
            }
    }

    fun stop() {
        stateChannel?.close()
        player?.stop()
        stopNetworkTask()
    }

    private fun stopNetworkTask() {
        val oldJob = taskJob
        taskJob = null

        // 不要在 oldJob 自己内部直接 oldJob.cancelAndJoin()，否则会等待自己结束。
        scope.launch {
            oldJob?.cancelAndJoin()
        }
    }

    private suspend fun failAnswer(cause: Throwable) {
        stateChannel?.send(StreamState.AnswerFailed(cause))
        stopNetworkTask()
    }

    private suspend fun failAudio(cause: Throwable) {
        stateChannel?.send(StreamState.AudioFailed(cause))
        stopNetworkTask()
    }
}
```

### 关键点

1. 两个连接并发建立，但必须同时成功才继续。
2. 任意一侧连接失败，要关闭另一侧已经成功的连接。
3. WebSocket 收集运行在子协程，SSE 文本在当前协程中处理。
4. 失败时取消整轮任务，不能只退出某一个 `collect`。
5. 外部不再收集状态 Flow 时，通过 `onCompletion` 取消当前任务。
6. 所有连接都需要在 `finally` 中兜底关闭。

---

## 4. 建立 SSE 连接

```kotlin
private suspend fun connectAnswer(
    question: String,
    token: String
): JdcrHttpResult<JdcrSseConnection> {
    return http.postSSEConnection(
        pathOrUrl = "/chat/stream?access_token=$token"
    ) {
        timeout(
            connectTimeoutMillis = 10_000L,
            socketTimeoutMillis = 10_000L
        )
        jsonBody(serialize(AnswerRequest(question)))
    }
}
```

### SSE 注意事项

- `connectTimeoutMillis` 只限制 TCP/TLS/请求建连阶段。
- `socketTimeoutMillis` 关注底层读写空闲，不等于“多久没收到一条完整 SSE 业务事件”。
- SSE 是长连接，不要把 `requestTimeoutMillis` 设置成 10 秒，否则整个请求会在 10 秒后被强制结束。
- 请求级 `timeout {}` 依赖客户端安装 `HttpTimeout`：

```kotlin
HttpClient(CIO) {
    install(HttpTimeout)
}
```

- 如果使用 `timeout {}` 但客户端没有安装 `HttpTimeout`，Ktor 会抛出：

```text
Consider installing HttpTimeout plugin because the request requires it to be installed
```

---

## 5. 收集 SSE 文本

```kotlin
private suspend fun collectAnswerEvents(
    connection: JdcrSseConnection,
    forward: suspend (AnswerText) -> Unit
) {
    var stopped = false
    var receivedBusinessFinish = false
    var firstFrame = true
    val completeText = StringBuilder()

    connection.events
        // onEach 在 takeWhile 之前：结束事件会先被处理，再停止收集。
        .onEach { result ->
            result
                .onSuccess { sseEvent ->
                    parseAnswer(sseEvent.data)
                        .onSuccess { answer ->
                            if (answer.isError) {
                                stopped = true
                                failAnswer(Exception(answer.errorMessage))
                                return@onSuccess
                            }

                            if (firstFrame) {
                                firstFrame = false
                                emitState(StreamState.AnswerReady(elapsedTime()))
                            }

                            completeText.append(answer.text)

                            if (answer.isEnd) {
                                receivedBusinessFinish = true
                                stopped = true

                                // 结束事件也要先转发给下游。
                                forward(AnswerText.Content(answer.text))
                                forward(AnswerText.Finished)
                            } else {
                                forward(AnswerText.Content(answer.text))
                            }
                        }
                        .onFailure { cause ->
                            stopped = true
                            failAnswer(cause)
                        }
                }
                .onFailure { failure ->
                    // JdcrHttpResult.Failure 是 Flow 中的一个值，不会进入 Flow.catch。
                    stopped = true
                    failAnswer(failure.toThrowable())
                }
        }
        .takeWhile { !stopped }
        .onCompletion { cause ->
            // EOF/正常 close 也可能发生在业务结束事件之前。
            if (cause == null && !receivedBusinessFinish && !stopped) {
                failAnswer(Exception("SSE 在业务结束事件之前关闭"))
            }
        }
        .collect()
}
```

### 为什么不能只写 `return@collect`

```kotlin
events.collect { event ->
    if (event.isEnd) {
        return@collect
    }
}
```

`return@collect` 只结束当前这一次 lambda 调用，不会终止整个 Flow。下一条数据到来时仍会继续执行。

需要真正结束收集时，可以使用：

- `take` / `takeWhile`；
- `transformWhile`；
- 取消负责收集的 Job；
- 关闭上游连接，但要确认关闭不会把正常业务结束传播成异常取消。

注意：`takeWhile` 默认会丢掉第一个不满足条件的值。要处理结束事件，应像示例一样先 `onEach`，再 `takeWhile`。

---

## 6. 建立 WebSocket 连接

```kotlin
private suspend fun connectAudio(
    token: String
): JdcrHttpResult<JdcrWsConnection> {
    val result = websocket.connectConnection(
        pathOrUrl = "/audio/stream?access_token=$token"
    ) {
        timeout {
            connectTimeoutMillis = 10_000L
            socketTimeoutMillis = 10_000L
        }
    }

    // 按业务需要关闭客户端 Ping；-1 表示关闭。
    result.getOrNull()?.configureHeartbeat(-1L)
    return result
}
```

WebSocket 请求上使用 `timeout {}` 时，创建 WebSocket `HttpClient` 的地方同样必须 `install(HttpTimeout)`。

不要把传输层超时当成业务数据超时：

- Socket 仍在收到零散字节时，`socketTimeoutMillis` 可能一直不会触发；
- 收到 Ping/Pong 不代表收到业务音频；
- 需要“10 秒没收到完整业务事件就失败”时，应在业务 `events` Flow 上增加空闲超时。

---

## 7. 收集 WebSocket 音频

```kotlin
@OptIn(FlowPreview::class)
private suspend fun collectAudioEvents(connection: JdcrWsConnection) {
    var buffer: AudioStreamBuffer? = null
    var firstAudioFrame = true

    // 必须区分“协议正常结束”和“连接提前结束”。
    var chatFinished = false

    try {
        connection.events
            .timeout(10.seconds)
            .catch { cause ->
                buffer?.streamEnd()

                if (cause is TimeoutCancellationException) {
                    failAudio(SocketTimeoutException("10 秒未收到完整音频事件"))
                } else {
                    // 不要把所有上游异常都伪装成超时。
                    throw cause
                }
            }
            .collect { result ->
                result
                    .onSuccess { wsEvent ->
                        // 日志要放在类型判断外，否则 Closed/Closing 会完全不可见。
                        log("收到 WebSocket 事件: $wsEvent")

                        when (wsEvent) {
                            is WsEvent.Text -> {
                                parseAudioEvent(wsEvent.data)
                                    .onSuccess { event ->
                                        when (event.type) {
                                            CHAT_STARTED -> {
                                                buffer = createPlayerBuffer(event.audioFormat)
                                            }

                                            AUDIO_CHUNK -> {
                                                buffer?.append(event.audioData)

                                                if (firstAudioFrame && buffer != null) {
                                                    firstAudioFrame = false
                                                    emitState(
                                                        StreamState.AudioReady(elapsedTime())
                                                    )
                                                }
                                            }

                                            SENTENCE_FINISHED -> {
                                                // 只表示一句话结束，不代表整轮对话结束。
                                            }

                                            CHAT_FINISHED -> {
                                                // 一定先记录业务正常结束，再主动 close。
                                                chatFinished = true
                                                buffer?.streamEnd()
                                                connection.close()
                                            }

                                            SERVER_ERROR -> {
                                                buffer?.streamEnd()
                                                failAudio(Exception(event.errorMessage))
                                            }
                                        }
                                    }
                                    .onFailure { cause ->
                                        buffer?.streamEnd()
                                        failAudio(cause)
                                    }
                            }

                            is WsEvent.Closed -> {
                                if (!chatFinished) {
                                    // Closed 是成功事件值，Flow.catch 不会捕获它。
                                    buffer?.streamEnd()
                                    failAudio(
                                        Exception(
                                            "音频连接提前关闭: ${wsEvent.reason.orEmpty()}"
                                        )
                                    )
                                }
                            }

                            is WsEvent.Closing -> {
                                // 通常只记录，等待 Closed 统一判断，避免报错两次。
                            }

                            WsEvent.Open,
                            is WsEvent.Binary -> {
                                // 按业务需要处理。
                            }
                        }
                    }
                    .onFailure { failure ->
                        // 网络库封装的 Failure 也是事件值，要显式处理。
                        buffer?.streamEnd()
                        failAudio(failure.toThrowable())
                    }
            }
    } finally {
        // 最后一层保险：任何退出路径都不能让播放器永远等待数据。
        buffer?.streamEnd()
    }
}
```

---

## 8. 最容易踩的坑：Closed 不会触发 timeout

下面是一个真实且容易误判的流程：

```text
收到部分数据
→ 用户断网
→ 网络库发出 Success(WsEvent.Closed)
→ 网络库正常 close events Flow
→ Flow.timeout 正常结束，不抛 TimeoutCancellationException
→ Flow.catch 不执行
```

如果业务代码只处理 Text：

```kotlin
result.onSuccess { event ->
    if (event is WsEvent.Text) {
        handleText(event)
    }
}
```

那么 `Open`、`Closing`、`Closed` 和 `Binary` 都会被静默忽略。播放器 Buffer 既没收到完整音频，也没收到 `streamEnd()`，最终可能一直停在“资源准备中”。

正确做法：

1. 记录是否收到协议级 `CHAT_FINISHED`；
2. 收到 `CHAT_FINISHED` 时先设置 `chatFinished = true`，再主动关闭连接；
3. 收到 `WsEvent.Closed` 且 `chatFinished == false` 时，按提前断开处理；
4. 所有失败、关闭和取消路径都结束播放器 Buffer；
5. 日志放在事件类型判断外，确保能看到 Closed。

结论：

```text
业务结束 != 连接结束
连接结束 != 超时
Flow 正常完成 != 业务成功
```

---

## 9. 三种超时不要混淆

| 超时 | 限制对象 | 适合判断 | 不适合判断 |
|---|---|---|---|
| `connectTimeoutMillis` | TCP/TLS/代理/握手连接阶段 | 能否及时连上服务 | 建连后是否持续有业务数据 |
| `socketTimeoutMillis` | 底层 Socket 相邻读写的空闲时间 | 连接是否长时间没有底层 IO | 是否收到完整 SSE/WS 业务事件 |
| `requestTimeoutMillis` | 整个 HTTP 请求的墙钟时长 | 普通短请求总耗时 | SSE/WS 长连接 |
| `Flow.timeout` | 上游两次 `emit` 之间的时间 | 是否长时间没有完整 Flow 事件 | 上游已经正常完成后的业务状态 |

### Flow.timeout 的语义

```kotlin
events
    .timeout(10.seconds)
    .catch { cause ->
        if (cause is TimeoutCancellationException) {
            handleBusinessIdleTimeout()
        } else {
            throw cause
        }
    }
    .collect(::handleEvent)
```

- 从开始 collect 到第一条数据，也会计算超时；
- 每次上游 emit 后重新计时；
- 下游处理数据花费的时间不计算在内；
- 上游正常完成时不会触发 timeout；
- 它统计的是 Flow 事件，不一定是底层网络字节；
- 如果 Flow 中还有 Open、Binary 等事件，这些事件同样会刷新计时；
- 如果只想判断“多久没有音频块”，应先过滤/转换成音频块 Flow，再使用 timeout。

`Flow.timeout` 在部分 kotlinx-coroutines 版本中属于 `FlowPreview`，使用前确认依赖版本并显式 `@OptIn(FlowPreview::class)`。

---

## 10. Flow 异常与结果值

JdcrHttp/JdcrWebSocket 常把网络失败封装为：

```kotlin
Flow<JdcrHttpResult<Event>>
```

因此存在两类失败：

### 1. 上游抛异常

```kotlin
events
    .catch { cause -> handleThrownException(cause) }
    .collect { result -> handleResult(result) }
```

### 2. Flow 正常 emit 一个 Failure 值

```kotlin
events.collect { result ->
    result
        .onSuccess(::handleEvent)
        .onFailure { handleFailureValue(it) }
}
```

第二种不会进入 `Flow.catch`。只写 `catch` 而不处理 `JdcrHttpResult.Failure`，会漏掉网络库已经上报的失败。

另外，`catch` 只捕获它上游的异常，捕获不到下游 `collect {}` 中抛出的异常。解析 JSON、写播放器 Buffer、发送状态等步骤要分别处理错误，或在更外层使用 `try/finally` 做资源收尾。

---

## 11. Ping/Pong 与业务超时

Ping/Pong 用于确认 WebSocket 链路是否还活着，不代表业务服务正在产生音频。

```kotlin
connection.configureHeartbeat(
    pingIntervalMillis = 15_000L,
    pongTimeoutMillis = 30_000L
)
```

关闭客户端 Ping：

```kotlin
connection.configureHeartbeat(-1L)
```

当前 JdcrWebSocket 读取层只记录 Ping/Pong，不把它们放进业务 `events` Flow，因此它们不会刷新业务层 `Flow.timeout`。如果以后修改网络库，把 Ping/Pong 也暴露到 events，需要重新确认超时语义。

通常可以同时存在两套检测：

- 心跳超时：判断链路是否存活；
- 业务 Flow 超时：判断是否长时间没有完整业务事件。

---

## 12. 播放器 Buffer 的生命周期

流式播放器通常会不断读取 Buffer：

```text
Buffer 有数据     → 返回音频字节
Buffer 暂时没数据 → 等待下一块
Buffer 已结束     → 返回 EOF
Buffer 已关闭     → 停止读取
```

因此，下列每条路径都要调用 `streamEnd()` 或等价的关闭方法：

- 收到 `CHAT_FINISHED`；
- 收到服务端错误事件；
- JSON/音频数据解析失败；
- 收到 `JdcrHttpResult.Failure`；
- 收到非预期 `WsEvent.Closed`；
- 业务 Flow 空闲超时；
- 用户取消任务；
- 页面销毁或 SDK release。

如果在收到第一块音频前就调用播放器 `prepare/start`，播放器可能合理地等待 Buffer。此时只要任何异常路径漏掉 `streamEnd()`，播放器就可能永久停在 PREPARING。

最稳妥的做法是在事件处理的 `finally` 中再执行一次幂等的 `buffer?.streamEnd()`，同时保留各错误分支中的及时收尾。

---

## 13. 取消与资源释放

### 新任务替换旧任务

```kotlin
taskJob?.cancel()
stateChannel?.cancel()
player?.stop()
```

### 外部停止收集

```kotlin
states.receiveAsFlow()
    .onCompletion {
        currentJob.cancel()
        states.cancel()
    }
```

### SDK 释放

```kotlin
fun release() {
    scope.cancelChildren()
    player?.release()
}
```

注意：

- `onCompletion` 在正常完成、异常和取消时都会执行；
- `onCompletion { cause -> cause == null }` 才表示正常完成；
- 不要把外部 `CancellationException` 转换成网络失败通知；
- 不要在当前 Job 内部对自己调用 `cancelAndJoin()`；
- 关闭状态 Channel 前，先确保关键失败状态已经成功发送。

---

## 14. 日志建议

排查流式连接时，至少记录以下信息：

```text
任务开始/任务 ID
SSE 和 WS 各自的连接结果
每个完整业务事件的类型和时间
所有 WsEvent 类型，包括 Open/Closing/Closed
是否收到 SSE isEnd
是否收到 WS CHAT_FINISHED
第一块文本和第一块音频耗时
Flow timeout 的原始 cause
Closed reason
Buffer 创建、首次 append、streamEnd、close
任务取消原因
```

不要在日志中输出完整 access token、Cookie、Authorization 或用户隐私数据。

---

## 15. 接入检查清单

### HttpClient

- [ ] HTTP 和 WebSocket 使用请求级 `timeout {}` 时都安装了 `HttpTimeout`
- [ ] SSE/WS 没有设置短 `requestTimeoutMillis`
- [ ] 明确 connect、socket、request、Flow timeout 的区别

### SSE

- [ ] 显式处理 `JdcrHttpResult.Failure`
- [ ] 显式处理 JSON/协议错误
- [ ] 记录并识别业务 `isEnd`
- [ ] 收到 `isEnd` 后真正终止收集，而不是只 `return@collect`
- [ ] Flow 正常完成但没有 `isEnd` 时按提前结束处理

### WebSocket

- [ ] 处理 Text、Closing、Closed 和网络 Failure
- [ ] 区分 `SENTENCE_FINISHED` 与 `CHAT_FINISHED`
- [ ] 收到 `CHAT_FINISHED` 时先设置完成标记，再关闭连接
- [ ] `Closed && !chatFinished` 按异常提前关闭处理
- [ ] Flow timeout 只用于上游仍存活但长时间没有 emit 的情况
- [ ] 根据业务明确 Ping/Pong 是否刷新空闲计时

### 播放器与任务

- [ ] 每个终止路径都会结束/关闭音频 Buffer
- [ ] 任意一侧失败都会取消整轮任务
- [ ] 新任务会取消旧任务
- [ ] 外部停止收集时会取消内部任务
- [ ] release 会取消子协程并释放播放器
- [ ] 状态 Flow 只由一个业务 collector 消费，或明确改成 SharedFlow 广播

只要上述清单全部满足，收到一半数据后断网、服务端提前关闭、上游静默卡住和用户主动取消等场景，都会有明确且可收尾的结果。
