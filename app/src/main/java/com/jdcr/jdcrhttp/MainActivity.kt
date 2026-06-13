package com.jdcr.jdcrhttp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.lifecycleScope
import com.jdcr.jdcrhttp.response.getOrElse
import com.jdcr.jdcrhttp.ui.theme.JdcrHttpTheme
import com.jdcr.jdcrhttp.util.JdcrHttpLog
import com.jdcr.jdcrwebsocket.JdcrWebSocketManager
import io.ktor.websocket.Frame
import io.ktor.websocket.readReason
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.jdcr.jdcrlog.JdcrLog
import io.ktor.client.request.header

val pureToken =
    "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJDb2RlbWFvIEF1dGgiLCJ1c2VyX3R5cGUiOiJzdHVkZW50IiwiZGV2aWNlX2lkIjo0ODAyMywidXNlcl9pZCI6MTAwMDcxMDkwOCwiaXNzIjoiQXV0aCBTZXJ2aWNlIiwicGlkIjoiQjNMOG9YVEsiLCJleHAiOjQxMDI0NDQ3OTksImlhdCI6MTc4MDA0ODY1NCwianRpIjoiOWFiNjBkNjYtMGU1MS00MzBhLWFiODItZDliNjg5NDk5N2U1In0.ZY8Yi2Hf2gid_NxmlHtwJyl2X6LOkOikgoJvYoe7oPQ"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        JdcrLog.enable(true)
        val ws = JdcrWebSocketManager.initInstance("")
        lifecycleScope.launch {
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

            }.getOrElse { JdcrHttpLog.e("连接失败", null) }
        }
        setContent {
            JdcrHttpTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    JdcrHttpTheme {
        Greeting("Android")
    }
}