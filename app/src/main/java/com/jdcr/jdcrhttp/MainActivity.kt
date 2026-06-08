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
import com.jdcr.jdcrhttp.ui.theme.JdcrHttpTheme
import com.jdcr.jdcrwebsocket.JdcrWebsocketManager
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readReason
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.jdcr.jdcrlog.JdcrLog

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        JdcrLog.enable(true, this.cacheDir.toString()+"/test/log.txt")
        val ws = JdcrWebsocketManager()
        lifecycleScope.launch {
            ws.webSocket("wss://echo.websocket.org") {

                launch {

                    val channel: ReceiveChannel<Frame> = incoming
                    for (frame in channel) {
                        when (frame) {
                            is Frame.Text -> {
                                val text = frame.readText()
                                println("📥 收到消息: $text")
                            }
                            is Frame.Close -> {
                                println("❌ 连接关闭: ${frame.readReason()}")
                            }

                            is Frame.Ping -> {
                                println("🏓 检测到Ping消息")
                            }

                            is Frame.Pong -> {
                                println("🏓 检测到Pong消息")
                            }

                            else -> Unit
                        }
                    }

                }

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

            }
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