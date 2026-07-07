package com.jdcr.jdcrhttp.test

import com.jdcr.jdcrbase.coroutine.JdcrSafeCoroutineScope
import com.jdcr.jdcrhttp.JdcrHttpManager
import com.jdcr.jdcrhttp.client.JdcrHttpClientFactory
import com.jdcr.jdcrhttp.response.JdcrSseLineParser
import com.jdcr.jdcrhttp.response.getOrElse
import com.jdcr.jdcrhttp.response.onFailure
import com.jdcr.jdcrhttp.response.onSuccess
import com.jdcr.jdcrhttp.util.JdcrHttpLog
import com.jdcr.jdcrwebsocket.JdcrWebSocketManager
import io.ktor.client.plugins.retry
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

//import okhttp3.Call
//import okhttp3.Callback
//import okhttp3.MediaType.Companion.toMediaType
//import okhttp3.OkHttpClient
//import okhttp3.Request
//import okhttp3.RequestBody.Companion.toRequestBody
//import okhttp3.Response
//import okio.IOException

object HttpTest {

    private val coroutine = JdcrSafeCoroutineScope()
    private var manager = JdcrHttpManager(JdcrHttpClientFactory.getDefaultHttp(), "")
    private val ws = JdcrWebSocketManager.initInstance("")

    fun destroy() {
        manager.destroyClient()
    }

    fun init() {
        manager = JdcrHttpManager.initInstance("")
    }

    fun sseGet() {
        coroutine.launch {
            manager.getSSEConnection("https://postman-echo.com/server-events/5", {
                header("Accept-Encoding", "identity")
            }).onSuccess {
                it.events.collect {
                    it.onSuccess {
                        JdcrHttpLog.d("接收的数据:${it.data}")
                    }
                }
            }.onFailure {
                JdcrHttpLog.d("通信失败失败")
            }
        }
    }

    fun ssePost() {
        coroutine.launch {
            manager.post<String>("", )
            val parser = JdcrSseLineParser()
            manager.postSSE("http://10.240.45.67:5001/sse", onLine = {
                parser.accept(it)?.also { JdcrHttpLog.d("解析:$it") }
            }, onClosed = {
                JdcrHttpLog.d("SSE关闭")
            }).onSuccess {

            }.onFailure {
                JdcrHttpLog.d("通信失败失败")
            }
        }
    }

    fun ssePostFlow() {
        coroutine.launch {
            manager.postSSEConnection("http://10.240.45.67:5001/sse", {
//                retry {
//                    maxRetries = 0 //post重试被排除了
//                }
                timeout {
                    connectTimeoutMillis = 5000
                }
            }).onSuccess {
                it.events.collect {
                    it.onSuccess {
                        JdcrHttpLog.d("接收的数据:${it.data}")
                    }
                }
            }.onFailure {
                JdcrHttpLog.d("通信失败失败")
            }
        }
    }

    fun ssePostOkHttp() {
//        val client = OkHttpClient.Builder()
//            .readTimeout(0, TimeUnit.SECONDS)      // 无限读取，SSE 必须
//            .connectTimeout(15, TimeUnit.SECONDS)
//            .build()
//
//        val request = Request.Builder()
//            .url("http://192.168.31.40:5001/sse")
////            .method("POST", null)
//            .post("{}".toRequestBody("application/json".toMediaType()))
//            .header("Accept", "text/event-stream")
//            .header("Cache-Control", "no-cache")
//            .build()
//
//        coroutine.launch {
//            client.newCall(request).enqueue(object : Callback {
//                override fun onFailure(call: Call, e: IOException) {
//                    JdcrHttpLog.w("okHttp请求失败${e.message}")
//                }
//
//                override fun onResponse(call: Call, response: Response) {
//                    if (!response.isSuccessful) {
//                        JdcrHttpLog.w("okHttp请求失败")
//                        return
//                    }
//
//                    // 直接读 OkHttp 的 BufferedSource，没有 Ktor ByteChannel 中间层
//                    response.body!!.source().use { source ->
//                        while (!source.exhausted()) {
//                            val line = source.readUtf8Line() ?: break
//                            JdcrHttpLog.i("okHttp获取的数据:$line")
//                            // 解析 SSE 协议
//                            if (line.startsWith("data: ")) {
//                                val data = line.removePrefix("data: ")
//                            }
//                            // 空行表示事件结束，这里不需要处理
//                        }
//                    }
//                }
//            })
//        }

    }

    fun ws() {
        val pureToken =
            "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJDb2RlbWFvIEF1dGgiLCJ1c2VyX3R5cGUiOiJzdHVkZW50IiwiZGV2aWNlX2lkIjo0ODAyMywidXNlcl9pZCI6MTAwMDcxMDkwOCwiaXNzIjoiQXV0aCBTZXJ2aWNlIiwicGlkIjoiQjNMOG9YVEsiLCJleHAiOjQxMDI0NDQ3OTksImlhdCI6MTc4MDA0ODY1NCwianRpIjoiOWFiNjBkNjYtMGU1MS00MzBhLWFiODItZDliNjg5NDk5N2U1In0.ZY8Yi2Hf2gid_NxmlHtwJyl2X6LOkOikgoJvYoe7oPQ"

        coroutine.launch {
            ws.connect(
                "wss://test-cone-ai-web.codemao.cn/ws/increment/bidi?access_token=$pureToken",
                {
                    header("Authorization", "Bearer $pureToken")
                }, {

                }) {

                // 发送测试消息
                val testMessages = listOf(
                    "Hello Ktor WebSocket!",
                    "测试中文消息",
                    "1234567890"
                )

                testMessages.forEach { message ->
                    println("📤 发送消息: $message")
                    send(Frame.Text(message))
                    delay(1000)
                }

                // 保持连接5秒后关闭
                delay(5000)
//                close(CloseReason(CloseReason.Codes.NORMAL, "测试完成"))

            }.getOrElse {
                JdcrHttpLog.e("连接失败", null)
            }
        }
    }

}