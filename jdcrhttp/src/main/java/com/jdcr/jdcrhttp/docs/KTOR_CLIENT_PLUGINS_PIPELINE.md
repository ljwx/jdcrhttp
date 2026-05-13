# Ktor Client：插件、`HttpSend`、`intercept` 与管线逻辑

本文总结 **Ktor HTTP Client（以 2.x 为主线）** 里与「插件」相关的常见概念：**插件是什么、`HttpSend` 干什么、`intercept` 怎么用、自定义插件里的 `on` / `Send` 钩子指什么**。具体类名与 lambda 签名请以你所使用的 **Ktor 版本** 源码 / [api.ktor.io](https://api.ktor.io/) 为准。

---

## 1. 插件（Plugin）在 Client 里是什么

- **`install(SomePlugin) { ... }`**：在创建 **`HttpClient { }`** 时执行 **一次**，用来 **注册** 一个 **`HttpClientPlugin`**，并把 **配置块** 交给该插件。
- 注册之后，请求发出时会经过 **插件构成的管线（pipeline）**：超时、重定向、序列化、日志、鉴权等多数能力都是 **插件** 实现的。
- **不是**：`install` 里的 lambda 在「每一次请求」完整重跑一遍；**是**：插件把「每次请求要经过的逻辑」注册进管线。

可以理解为：**`install` = 装配流水线零件；真正流水是在每次 `get/post/...` 时跑的。**

---

## 2. `HttpSend` 插件是干什么的

**`HttpSend`**（`io.ktor.client.plugins.HttpSend`）负责客户端侧 **「如何把一次调用交给引擎发出去」** 这一段：

- 它处在 **`HttpRequestBuilder` / 各业务插件** 与 **`Engine` 真正 IO** 之间。
- 许多「**在发送前后插一脚**」的能力（重试、自定义头等）会挂在 **`HttpSend` 提供的扩展点**上，而不是直接改引擎。

可以把它记成：**发送链路的「最后一站调度」**，对外暴露 **`intercept`**，让你在这条链路上 **包一层又一层**。

> 注意：不同版本里 **`HttpSend` 是否默认安装、是否需要显式 `install(HttpSend)`** 可能不同；若文档要求显式安装，再写上即可。

---

## 3. `intercept` 的作用和用法（针对 `HttpSend`）

**语义**：在 **`HttpSend`** 的发送流程上 **插入一段拦截逻辑**，典型用途：

- 给 **`HttpRequest`** / **`HttpRequestBuilder`** 追加 Header（例如自定义 `token` 头）；
- 在真正 `execute` 前后打日志、打点；
- 与其它拦截器形成 **洋葱模型**：先执行外层 `intercept`，再进入内层，直到真正发到引擎。

**直观理解**：  
`intercept { ... }` 收到「下一步该怎么发」的 **`sender`**（或等价抽象），你在里面可以：

1. 改请求（headers、url 等）；
2. 调用 **`execute(...)`**（名字以版本为准）把请求继续往下传；
3. 拿到 **`HttpClientCall`** / 响应后再返回（或在这里做重试包装）。

**要点**：

- **`intercept` 通常是 `suspend`**：可以在拦截器里 **挂起** 读 token、刷新会话等（具体能否挂起以签名为准）。
- **多个 `intercept`**：按注册顺序 **层层包裹**，顺序会影响谁先谁后（例如先加头再记日志，或相反）。

**典型用途对照**：

| 需求 | 是否优先考虑 `HttpSend.intercept` |
|------|-------------------------------------|
| 统一加自定义 Header（如 `token`） | ✅ 常见 |
| 只做静态默认头 | `defaultRequest` 往往更简单 |
| 标准 `Authorization: Bearer ...` | 优先 **`Auth` + `bearer`** |

---

## 4. 自定义插件里的 `on`、以及 `Send` 相关钩子

当你用 **`createClientPlugin`**（或当前版本推荐的等价 API）编写 **自己的 Client 插件**时，通常会看到类似：

```text
on(Send) { ... }
```

或文档里的 **`Send`**、**`Request`** 等 **管线事件（hook）**。

**含义（概念层）**：

- **`Send`**：对应「请求即将 / 正在进入发送阶段」这一类事件；在这里改 **`HttpRequestBuilder`**，等同于挂在「发送管线」上。
- **`on(...)`**：表示 **订阅某一管线节点**：插件在 **每次请求**经过该节点时执行你注册的 lambda。

因此：

- **`install(HttpSend) { intercept { } }`**：使用 **框架内置的 `HttpSend` 插件**，在它的 **`intercept` 槽位**里写逻辑；
- **`createClientPlugin { on(Send) { } }`**：**自定义一个新插件**，内部订阅 **`Send`**（或其它 hook），复用同一套管线模型。

两者差别主要是：**前者用现成插件扩展开口；后者自己占位成一个可 `install` 的插件**，便于配置项复用、命名、多实例。

> 不同 Ktor 小版本里，**钩子的名字、`ClientPluginBuilder` 的方法**可能微调；以当前版本的 **`createClientPlugin` 示例与源码**为准。

---

## 5. 与其它机制的对比（避免混用冲突）

| 机制 | 何时执行 | 典型用途 |
|------|-----------|----------|
| **`defaultRequest { }`** | 每次请求构造默认 URL/Headers 时 | 静态 UA、公共 Query、`Accept-Language`；读内存里的 token 也可 |
| **`install(Auth) { bearer { ... } }`** | Auth 插件管线 | **`Authorization: Bearer ...`**（RFC 常见写法） |
| **`HttpSend.intercept`** | 发送链路中 | 自定义头、统一改写、与发送强绑定的逻辑 |
| **`Logging` / `HttpTimeout` 等** | 各自插件注册的管线位置 | 日志、超时、重定向、重试等 |

**同一请求**可能先后经过：**defaultRequest 合并 → 各插件 → HttpSend（含 intercept）→ Engine**。  
因此 **安装顺序**（`install` 的先后顺序）有时会改变最终行为（例如超时与重试的顺序，官方文档会单独说明）。

---

## 6. 实操建议（精简）

1. **标准 Bearer**：用 **`Auth` + `bearer`**，不要用 `HttpSend` 重复造 `Authorization`。
2. **后端要自定义头名（如 `token`）**：优先 **`defaultRequest`** 读内存 token；需要 **suspend** 或更强管线控制再上 **`HttpSend.intercept`** 或 **自定义插件**。
3. **自定义插件**：适合多处复用、可配置项多；简单一行头优先 **`defaultRequest`**。
4. 升级 Ktor 时：**优先对照官方「Breaking changes」与 `HttpSend` / `createClientPlugin` 签名**。

---

## 7. 官方文档入口

- [HTTP Client 插件](https://ktor.io/docs/client-plugins.html)  
- [Creating custom client plugins](https://ktor.io/docs/client-custom-plugins.html)（若当前版本文档路径不同，从官网导航进入「Client → Plugins」）  
- [HttpSend（API）](https://api.ktor.io/) 搜索 `HttpSend`  

---

文档版本：概念说明向；与具体业务封装（如工厂类）无关，可与同目录 `KTOR_HTTP_CLIENT_CONFIG.md` 配套阅读。
