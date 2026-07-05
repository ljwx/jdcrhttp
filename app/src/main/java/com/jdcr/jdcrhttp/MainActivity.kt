package com.jdcr.jdcrhttp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.jdcr.jdcrhttp.test.HttpTest
import com.jdcr.jdcrhttp.ui.theme.JdcrHttpTheme
import com.jdcr.jdcrwebsocket.JdcrWebSocketManager
import com.jdcr.jdcrlog.JdcrLog
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        JdcrLog.enable(true)
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
    val ws = JdcrWebSocketManager.initInstance("")
    Column(modifier = modifier) {
        Row {
            Text(
                text = "Hello $name!",
            )
        }
        Row {
            Button(onClick = {
                HttpTest.destroy()
            }) {
                Text("销毁")
            }
            Button(onClick = {
                HttpTest.init()
            }) {
                Text("重置")
            }
        }
        Row {
            Button(onClick = {
                HttpTest.sseGet()
            }) {
                Text("sseGet")
            }
            Button(onClick = {
                HttpTest.ssePost()
            }) {
                Text("ssePost")
            }
            Button(onClick = {
                HttpTest.ws()
            }) {
                Text("ws")
            }
        }
        Row {
            Button(onClick = {
                HttpTest.ssePostOkHttp()
            }) {
                Text("ssePostOkHttp")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    JdcrHttpTheme {
        Greeting("Android")
    }
}