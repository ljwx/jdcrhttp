# Ktor HTTP Client 配置说明（通用）

本文面向 **Ktor Client**（以 **2.x** 为主线），说明客户端组成、引擎、`HttpClientConfig` 与常用 **插件** 的职责及典型配置思路，便于与 [官方文档](https://ktor.io/docs/client.html) 对照阅读。

> 说明：下文描述的是 **Ktor 框架本身** 的概念与选项；若你在业务里做了封装，仍以实际代码为准。

---

## 1. 整体结构

```
HttpClient(Engine) {
    // HttpClientConfig：引擎 + 插件 + 默认请求等
}
```

| 概念 | 含义 |
|------|------|
| **Engine（引擎）** | 真正发起 TCP/TLS、读写套接字的实现，如 **CIO**、**OkHttp**、**Android**、**Java** 等 |
| **Plugins（插件）** | 挂在请求/响应管道上的能力：超时、序列化、日志、鉴权、重定向、重试、Cookie、压缩等 |
| **defaultRequest** | 对每一次请求生效的默认块（Header、Host、参数等） |

同一套 **插件配置** 在不同引擎上行为大体一致；**连接池、TLS、DNS** 等差异主要体现在 **Engine 配置**里。

---

## 2. 引擎（Client Engine）简述

| 引擎 | Gradle 依赖（示例） | 典型场景 |
|------|---------------------|----------|
| **CIO** | `ktor-client-cio` | Kotlin Multiplatform / JVM / Android；自带连接池与 endpoint 参数 |
| **OkHttp** | `ktor-client-okhttp` | JVM/Android，与既有 OkHttp 拦截器、Pinning、调试工具链一致 |
| **Android** | `ktor-client-android` | 使用 Android 侧 HTTP 栈 |
| **Java** | `ktor-client-java` | JDK `HttpClient`（版本要求见官方说明） |

引擎配置在：

```kotlin
HttpClient(OkHttp) { // 或 CIO / Android / Java
    engine {
        // 仅当前引擎支持的选项，例如 OkHttp 的 Builder、CIO 的 endpoint 等
    }
}
```

---

## 3. `HttpClientConfig` 层常用设置

| 属性 / API | 作用 |
|------------|------|
| **`expectSuccess`** | 若为 `true`，响应状态码非 **2xx** 时抛出 **`ResponseException`**（可在全局统一失败路径）；若为 `false`，需自行根据 `HttpResponse.status` 处理 |
| **`followRedirects`** | 注意：是否跟随重定向通常由 **`HttpRedirect` 插件** 控制；具体以你所用 Ktor 版本文档为准 |
| **`defaultRequest { }`** | 为所有请求设置默认 Host、Header、参数等 |

---

## 4. 常用插件一览（安装顺序可能影响行为）

安装方式统一为：`install(Plugin) { ... }`。

### 4.1 `HttpTimeout`

| 作用 | 控制连接、Socket、整次请求的时限（跨引擎的统一抽象） |
|------|--------------------------------------------------------|
| 典型字段 | `connectTimeoutMillis`、`socketTimeoutMillis`、`requestTimeoutMillis` |

常与引擎自带的连接/读写超时 **并存**；具体以引擎实现为准，通常建议 **数值不要互相矛盾**。

---

### 4.2 `ContentNegotiation`

| 作用 | 请求/响应 Body 与 `Content-Type` 的序列化与反序列化 |
|------|------------------------------------------------------|
| 常见扩展 | `kotlinx.serialization`、`gson`、`jackson` 等 |

需在依赖中引入对应的 **`ktor-serialization-*`** 模块。

---

### 4.3 `Logging`

| 作用 | 输出请求/响应摘要或 BODY（取决于 `LogLevel`） |
|------|-----------------------------------------------|
| 注意 | 生产环境慎用 **BODY**，避免泄露隐私与增大日志 |

- NONE：不打印
- INFO：打印请求行/响应行等关键信息（适合日常调试）
- HEADERS：加上请求头和响应头
- BODY / ALL：连 Body 都打（最详细，排查接口问题最有用）
---

### 4.4 `Auth`

| 作用 | Bearer、Basic、Digest 等鉴权 |
|------|------------------------------|
| 典型用法 | Bearer：`loadTokens` / `refreshTokens`（刷新逻辑按业务实现） |

---

### 4.5 `WebSockets`

| 作用 | 启用客户端 **`webSocket { }`** 会话 |
|------|--------------------------------------|

未安装时无法使用 Ktor 提供的 WebSocket Client API。

---

### 4.6 `HttpRedirect`

| 作用 | 自动跟随 **301/302** 等重定向 |
|------|-------------------------------|
| 常见配置 | 是否允许 HTTPS 降级、是否校验 HTTP 方法等（见对应版本的 `HttpRedirect.Config`） |

---

### 4.7 `HttpRequestRetry`

| 作用 | 按条件重试请求（如 **5xx**、特定异常） |
|------|----------------------------------------|
| 常见配置 | `maxRetries`、`retryIf`、`retryOnExceptionIf`、延迟策略（常量 / 指数退避等） |

**注意**：与引擎层「连接失败再拨号」不是同一概念；对 **POST** 等非幂等请求要特别谨慎。

官方文档中会说明 **`HttpTimeout` 与 `HttpRequestRetry` 的安装顺序**对「超时重试」的影响，升级版本时请核对。

---

### 4.8 `HttpCookies`

| 作用 | 维护 Cookie 存储（内存 / 持久化由 `CookiesStorage` 决定） |
|------|-----------------------------------------------------------|

---

### 4.9 `ContentEncoding`（压缩）

| 作用 | 声明并处理 **gzip / deflate** 等（需引入 **`ktor-client-encoding`**） |
|------|------------------------------------------------------------------------|

有利于减少传输体积；极少数服务端对 `Accept-Encoding` 行为特殊时需单独验证。

---

### 4.10 其它（按需）

| 插件 | 作用 |
|------|------|
| **`HttpSend`** / 自定义管线 | 拦截、改写、重放请求（高级用法） |
| **`HttpResponseValidator`** | 统一校验响应（例如根据业务 body 抛错） |

---

## 5. 「引擎超时」和 `HttpTimeout` 为何可能都存在

| 层次 | 典型关注点 |
|------|------------|
| **引擎** | 底层建连、Socket 读写、部分引擎还有「整请求」上限（如 CIO 的 `requestTimeout`） |
| **`HttpTimeout` 插件** | 在 Ktor 客户端管道上统一三类超时，便于跨引擎配置 |

二者可能 **语义重叠**；实践上常用 **一套业务超时配置**，同时写入引擎（若支持）与 `HttpTimeout`，并保持数值一致或明确优先级。

---

## 6. 官方文档入口（用于查最新 API）

- [Ktor Client 概述](https://ktor.io/docs/client.html)  
- [Engines（引擎）](https://ktor.io/docs/client-engines.html)  
- [Timeout（超时）](https://ktor.io/docs/client-timeout.html)  
- [Serialization（序列化）](https://ktor.io/docs/client-serialization.html)  
- [Logging](https://ktor.io/docs/client-logging.html)  
- [Authentication](https://ktor.io/docs/client-auth.html)  
- [WebSockets](https://ktor.io/docs/client-websockets.html)  
- [Redirects](https://ktor.io/docs/client-redirects.html)  

版本差异以你所使用的 **Ktor 版本**对应的 API 文档为准。

---

## 7. 小结

- **引擎**决定「怎么连」；**插件**决定「HTTP 语义与横切能力」。  
- **超时**往往在引擎与 **`HttpTimeout`** 两处都可配置，需对照文档避免打架。  
- **重试 / 重定向 / Cookie / 压缩** 均为可选插件，按业务打开即可。  

如需针对某一引擎（例如 **仅 CIO** 或 **仅 OkHttp**）的字段级清单，建议直接打开对应版本的 **api.ktor.io** 中 `CIOEngineConfig`、`OkHttpConfig` 等类页。
