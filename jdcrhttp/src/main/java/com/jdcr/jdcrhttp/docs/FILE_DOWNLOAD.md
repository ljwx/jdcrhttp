# JdcrHttp 文件下载

这套下载 API 把职责分成三层：

```text
UI / ViewModel       展示状态，决定开始、取消、重试
WorkManager（可选）   需要离开页面或进程重启后继续时，托管任务生命周期
JdcrHttp downloader  流式读写、Range 续传、校验、临时文件与原子落盘
```

`downloadFile()` 返回冷 `Flow`。开始收集才会发起请求，取消收集它的协程会立即取消
网络读取。未完成内容保存在同目录的 `.jdcr.part` 文件中；只有完整下载并通过校验后，
它才会原子替换目标文件。

## 1. 源码入口和职责

| 源码 | 职责 |
| --- | --- |
| [`JdcrHttpCore.downloadFile()`](../JdcrHttpCore.kt) | 构造请求参数，返回下载 Flow |
| [`JdcrDownloadRequest`](../download/JdcrDownloadRequest.kt) | URL、目标文件、续传、覆盖、SHA-256 和进度配置 |
| [`JdcrDownloadState`](../download/JdcrDownloadState.kt) | 对外输出准备、开始、进度、完成和失败状态 |
| [`JdcrFileDownloader`](../download/JdcrFileDownloader.kt) | 下载、断点续传、校验和文件落盘的实际实现 |

一次请求使用三个同目录文件。假设目标是 `package.apk`：

| 文件 | 用途 | 什么时候删除 |
| --- | --- | --- |
| `package.apk` | 业务最终读取的完整文件 | 下载器不会提前删除；新文件完成后原子替换 |
| `package.apk.jdcr.part` | 正在下载的临时文件 | 下载成功、SHA-256 失败、断点不可信或禁用续传时 |
| `package.apk.jdcr.part.meta` | 续传所需的来源和版本信息 | 下载成功、无法安全续传或禁用续传时 |

临时文件必须和目标文件放在同一个目录。这样最后的 `rename` 在同一文件系统内完成，
业务不会读到只写了一半的目标文件。

## 2. 一次下载的完整状态流

```text
collect downloadFile()
        |
        v
Preparing
        |
        +-- 对目标路径加 Mutex，防止同一目标被并发写入
        |
        +-- 已有目标且 overwriteExisting=false
        |       |
        |       +-- 可选 SHA-256 校验 --> Completed(fromExistingFile=true)
        |
        +-- 检查 .part 和 .meta 是否可以续传
                |
                +-- 不可续传 --> GET，从 0 写入
                |
                +-- 可以续传 --> GET + Range + If-Range，追加写入
                                      |
                                      v
                         Started --> Progress ...
                                      |
                    长度校验 --> SHA-256 校验
                                      |
                              原子替换目标文件
                                      |
                                  Completed
```

任意非取消异常会转换为 `JdcrDownloadState.Failed`。`CancellationException` 不会被包装，
而是继续向上抛出，因此取消收集 Flow 就能真正取消 Ktor 请求。

核心结构对应 `JdcrFileDownloader.download()`：

```kotlin
flow {
    emit(JdcrDownloadState.Preparing(request.destination))

    try {
        destinationMutex.withLock {
            downloadLocked(request, options)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        emit(JdcrDownloadState.Failed(...))
    }
}.flowOn(Dispatchers.IO)
```

整个 Flow 在 `Dispatchers.IO` 执行，网络读取和 `RandomAccessFile` 写入不会占用主线程。
Flow 是冷的，同一个 Flow 收集两次代表执行两次下载流程。

## 3. 普通下载逻辑

没有可用断点时，请求不发送 `Range`，正常响应应为 `200 OK`。

### 3.1 请求阶段

下载器会执行以下配置：

```kotlin
client.prepareGet {
    url(resolvedUrl)
    expectSuccess = false
    timeout {
        requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
    }
    applyJdcrRequest(options)

    headers.remove(HttpHeaders.AcceptEncoding)
    headers.append(HttpHeaders.AcceptEncoding, "identity")
}
```

- `expectSuccess = false`：下载器需要自己区分 `200`、`206` 和 `416`，不能让 Ktor 提前
  把 `416` 转成异常。
- 整体请求超时默认为无限：大文件不应该因为总耗时超过普通 API 的 30 秒而失败。
  建连超时和 Socket 空闲超时仍然生效，也可以在请求块中覆盖。
- `Accept-Encoding: identity`：字节范围和文件偏移必须针对原始文件。若响应被 gzip 解压，
  HTTP 字节位置就不再等于本地文件位置，断点可能错位。

### 3.2 流式写入

拿到 `200` 后，下载器读取 `bodyAsChannel()`，每次最多读取 `bufferSize` 字节。默认缓冲区
为 64 KiB，不会把整个响应体放进内存。

```kotlin
RandomAccessFile(partFile, "rw").use { output ->
    output.setLength(0L)

    while (true) {
        val read = body.readAvailable(buffer)
        if (read == -1) break
        if (read == 0) continue

        output.write(buffer, 0, read)
        digest?.update(buffer, 0, read)
        bytesDownloaded += read
        emitProgressWhenNeeded()
    }

    output.fd.sync()
}
```

这里有四个关键点：

1. `200` 一定调用 `setLength(0)`，即使本地有旧的 `.part`，也不会把完整响应追加到旧数据后面。
2. 每写一块就同步更新字节数和可选 SHA-256，不需要二次缓存响应体。
3. 进度默认最多每 200 ms 发一次，避免高速下载时频繁刷新 UI；结束时会补发最后一次进度。
4. 写完调用 `FileDescriptor.sync()`，数据刷新到存储后才进入完整性检查和重命名。

如果服务端返回 `Content-Length`，下载器要求实际读取字节数与它完全一致。响应提前 EOF
不会被当成成功。

## 4. 断点续传逻辑

断点续传不是看到 `.part` 就直接追加。必须同时满足以下条件：

1. `resume = true`。
2. `.part` 存在且长度大于 0。
3. `.meta` 能正常解析。
4. `.meta` 中的 `source` 等于本次完整 URL 的 SHA-256。
5. `.meta` 中存在可用于 `If-Range` 的强 ETag 或 Last-Modified。

`source` 保存 URL 的摘要而不是原始 URL，避免把 URL query 中的 Token 明文写进磁盘。
弱 ETag，例如 `W/"version"`，不能用于 `If-Range`，所以不会被当作续传凭据。

新响应到达后，下载器优先选择强 ETag，没有强 ETag 时再选择 Last-Modified。它会在读取
响应体之前先把校验标识写入 `.meta` 并调用 `sync()`，这样传输中途取消或断网时，已经写入
`.part` 的字节仍有与之对应的版本凭据。

元数据内容等价于：

```properties
source=<完整 URL 的 SHA-256>
validatorKind=ETAG
validatorValue="file-version-123"
```

任何一项不匹配，下载器都会删除旧 `.part` 和 `.meta`，从 0 开始，避免拼接不同 URL
或不同版本的内容。

### 4.1 续传请求

假设 `.part` 已有 `1048576` 字节，请求会增加：

```http
Range: bytes=1048576-
If-Range: "file-version-123"
Accept-Encoding: identity
```

`Range` 表示只请求偏移 1048576 之后的内容；`If-Range` 表示只有服务器上的文件仍是
同一个版本时才返回剩余部分。文件已变化时，符合协议的服务器应返回完整的 `200`。

### 4.2 响应分支

| 响应 | 内部处理 |
| --- | --- |
| `200 OK` | 服务器忽略 Range 或文件版本已变化；调用 `setLength(0)`，从头安全重写 |
| `206 Partial Content` | 校验 `Content-Range` 后，从 `.part.length()` 位置追加 |
| `416 Range Not Satisfiable`，且 `Content-Range: bytes */N` 中 `N == .part.length()` | 本地临时文件其实已经完整，直接进入文件校验 |
| 其他 `416` | 清理旧断点并从 0 重试一次；再次失败则返回协议错误 |
| 其他状态码 | 转换为 `JdcrHttpResult.Failure`，通过 `Failed` 发出 |

对于 `206`，不能只看状态码。下载器还会验证：

```text
Content-Range: bytes start-end/total

start == 本地 .part 长度
end >= start
end < total（total 不是 * 时）
Content-Length == end - start + 1（存在 Content-Length 时）
响应 ETag / Last-Modified 与本地元数据一致（响应提供校验标识时）
```

全部通过后才执行：

```kotlin
output.seek(partFile.length())
output.write(newBytes)
```

传输结束后还会再次检查：

```text
本次实际读取字节数 == 响应 Content-Length
累计文件字节数 == Content-Range 中的 total
```

所以错误的 Range 起点、截断响应或服务端版本变化都不会生成最终文件。

### 4.3 取消后为什么能继续

读取协程被取消时，`RandomAccessFile.use` 会关闭文件，但不会删除 `.part` 和 `.meta`。
下次使用相同 URL 和目标路径调用 `downloadFile()` 时，会重新读取 `.part.length()` 和
`.meta`，再构造新的 Range 请求。

如果服务器没有提供强 ETag 或 Last-Modified，临时数据可以在当前进程中保留，但下一次
调用不会冒险续传，而会从 0 重写。数据正确性优先于节省流量。

## 5. 文件校验逻辑

校验由两个参数控制：

| 参数 | 行为 |
| --- | --- |
| `calculateSha256 = true` | 计算 SHA-256，并在 `Completed.sha256` 返回，不比较预期值 |
| `expectedSha256 = "..."` | 自动启用计算，并要求实际值与预期值一致 |

`expectedSha256` 必须是 64 位十六进制字符串，比较前会去除首尾空格并转为小写。

### 5.1 普通下载的计算方式

每次把网络数据写进 `.part` 时，同时调用：

```kotlin
digest.update(buffer, 0, read)
```

网络读取结束后通过 `digest.digest()` 得到整个文件的 SHA-256。这不会再读取一次文件。

### 5.2 续传下载的计算方式

SHA-256 是基于完整文件的，不能只计算本次新增部分。因此续传开始前，先把已有 `.part`
喂给同一个 `MessageDigest`，然后继续加入网络新数据：

```kotlin
if (offset > 0L) {
    digest.updateFrom(partFile)
}

while (downloading) {
    digest.update(newBytes)
}
```

这样最终摘要等价于从头计算完整文件。没有把 `MessageDigest` 的内部状态写入 `.meta`，
因为它不是稳定、可移植的持久化格式。

### 5.3 校验成功和失败

处理顺序固定为：

```text
响应长度检查
    -> 文件总长度检查
    -> SHA-256 检查
    -> Os.rename(.part, destination)
    -> 删除 .meta
    -> Completed
```

SHA-256 不匹配时：

- 删除 `.part`，防止下次从已知错误的数据继续下载。
- 删除 `.meta`。
- 抛出 `JdcrDownloadChecksumException`，最终转换为 `Failed`。
- 不删除也不覆盖旧的目标文件。

校验成功后使用 Android `Os.rename()` 原子替换目标文件。即使
`overwriteExisting = true`，旧目标也会一直保留到新文件完整并通过校验之后。

已有目标文件且 `overwriteExisting = false` 时，不发 HTTP 请求。如果配置了 SHA-256，
会先校验已有文件；通过后返回 `Completed(fromExistingFile = true)`。

## 6. 状态字段怎么理解

| 状态 | 关键字段 |
| --- | --- |
| `Preparing` | 已开始收集，正在等待目标文件锁或检查本地文件 |
| `Started` | `bytesDownloaded` 是起始偏移；`resumed` 表示本次是否实际追加 |
| `Progress` | `bytesDownloaded` 是完整文件累计值；`totalBytes` 可能为空；`bytesPerSecond` 只统计本次网络传输 |
| `Completed` | `sha256` 仅在启用计算时存在；`fromExistingFile` 表示是否直接复用了最终文件 |
| `Failed` | `partialFile` 表示仍存在临时文件；`canResume` 表示它是否具备安全续传元数据 |

同一个 `JdcrHttpManager` 会按目标文件的规范路径取得 `Mutex`。两个协程同时下载到同一个
目标时，第二个会停在 `Preparing` 后等待，避免两个响应交叉写入同一 `.part`。

## 7. 页面内下载

适用于页面仍在前台时执行的下载。目标放在 app 私有目录，不需要存储权限。

```kotlin
class DownloadViewModel(
    private val http: JdcrHttpManager,
) : ViewModel() {

    private val _state = MutableStateFlow<DownloadUiState>(DownloadUiState.Idle)
    val state: StateFlow<DownloadUiState> = _state.asStateFlow()

    private var downloadJob: Job? = null

    fun start(context: Context, url: String, sha256: String? = null) {
        downloadJob?.cancel()

        val target = File(context.filesDir, "downloads/package.apk")
        val request = JdcrDownloadRequest(
            pathOrUrl = url,
            destination = target,
            resume = true,
            overwriteExisting = true,
            expectedSha256 = sha256,
        )

        downloadJob = http.downloadFile(request) {
            requireAuth()
            timeout(
                connectTimeoutMillis = 15_000,
                socketTimeoutMillis = 30_000,
                // 不设置 requestTimeoutMillis，下载器默认不限制整个下载耗时。
            )
        }.onEach { event ->
            _state.value = when (event) {
                is JdcrDownloadState.Preparing -> DownloadUiState.Preparing

                is JdcrDownloadState.Started -> DownloadUiState.Downloading(
                    downloaded = event.bytesDownloaded,
                    total = event.totalBytes,
                    speed = 0,
                )

                is JdcrDownloadState.Progress -> DownloadUiState.Downloading(
                    downloaded = event.bytesDownloaded,
                    total = event.totalBytes,
                    speed = event.bytesPerSecond,
                )

                is JdcrDownloadState.Completed -> DownloadUiState.Succeeded(event.destination)

                is JdcrDownloadState.Failed -> DownloadUiState.Failed(
                    message = event.error.message,
                    canResume = event.canResume,
                )
            }
        }.launchIn(viewModelScope)
    }

    fun cancel() {
        downloadJob?.cancel()
    }
}

sealed interface DownloadUiState {
    object Idle : DownloadUiState
    object Preparing : DownloadUiState
    data class Downloading(val downloaded: Long, val total: Long?, val speed: Long) : DownloadUiState
    data class Succeeded(val file: File) : DownloadUiState
    data class Failed(val message: String, val canResume: Boolean) : DownloadUiState
}
```

重新调用相同请求即可续传。只有服务端提供强 ETag 或 Last-Modified，并正确支持
`Range` / `If-Range` 时，`canResume` 才会是 `true`。服务器不支持 Range 时会安全地从头
写入，不会把新旧内容拼接。

## 8. 需要后台可靠执行时

不要从单例里创建一个无法追踪的全局协程。使用 WorkManager 托管任务，并直接收集同一个
下载 Flow：

```kotlin
class FileDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val fileName = inputData.getString(KEY_FILE_NAME) ?: return Result.failure()
        val target = File(applicationContext.filesDir, "downloads/$fileName")

        var terminalResult: Result = Result.retry()

        JdcrHttpManager.instance().downloadFile(
            JdcrDownloadRequest(
                pathOrUrl = url,
                destination = target,
                resume = true,
                overwriteExisting = true,
                expectedSha256 = inputData.getString(KEY_SHA_256),
            )
        ).collect { event ->
            when (event) {
                is JdcrDownloadState.Progress -> setProgress(
                    workDataOf(
                        "downloaded" to event.bytesDownloaded,
                        "total" to (event.totalBytes ?: -1L),
                    )
                )

                is JdcrDownloadState.Completed -> terminalResult = Result.success()
                is JdcrDownloadState.Failed -> {
                    terminalResult = when (val error = event.error) {
                        is JdcrHttpResult.Failure.ConnectError,
                        is JdcrHttpResult.Failure.LocalError.Network,
                        is JdcrHttpResult.Failure.LocalError.Timeout -> Result.retry()

                        is JdcrHttpResult.Failure.HttpError -> {
                            if (error.code == 408 || error.code == 429 || error.code >= 500) {
                                Result.retry()
                            } else {
                                Result.failure()
                            }
                        }

                        else -> Result.failure()
                    }
                }

                else -> Unit
            }
        }
        return terminalResult
    }
}
```

调度 Worker 时建议设置 `NetworkType.CONNECTED`、指数退避，以及大文件所需的前台通知。
`JdcrHttpManager` 应在 `Application` 中初始化。

## 9. 行为约定

- 同一个 `JdcrHttpManager` 对相同目标路径的并发下载会串行执行，避免两个响应同时写文件。
- `overwriteExisting = false` 时，已有目标文件会直接返回 `Completed(fromExistingFile=true)`；
  若提供 SHA-256，则会先校验。
- `expectedSha256` 校验失败会删除临时文件，但不会删除旧的目标文件。
- HTTP 和本地错误通过 `JdcrDownloadState.Failed` 返回；协程取消仍以
  `CancellationException` 传播，不会被包装成失败状态。
- `canResume` 只表示已有临时文件具备安全续传条件，不表示当前错误一定值得自动重试；
  WorkManager 应仅对网络、超时、限流和服务端错误执行退避重试。
- `File` 目标适合 app 私有目录。写入系统“下载”目录时，应由 App 通过 MediaStore 创建
  目标，或下载到私有目录后再导出，以符合 Android 分区存储规则。
