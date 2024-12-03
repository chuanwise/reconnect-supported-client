/*
 * Copyright 2024 Chuanwise.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.chuanwise.reconnect.supported.client

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

const val HOST = "localhost"
const val PORT = 11451
const val PATH = "/"

private val logger = KotlinLogging.logger {  }

class WebSocketReconnectSupportedClientTest {
    private fun createWebSocketServer() = embeddedServer(
        factory = Netty,
        port = PORT,
        host = HOST,
        module = Application::module
    ).start()

    @Test
    fun testReconnect(): Unit = runBlocking {
        var server = createWebSocketServer()
        val client = WebSocketReconnectSupportedClient(HOST, PORT, PATH, logger = logger).apply { start() }
        client.awaitUntilConnected()

        client.send("要始终把经历放在解决问题上面")
        delay(114)

        client.send("不用自怨自艾")
        delay(514)

        client.send("因为不管你关不关注")
        delay(114)

        client.send("问题都是那个样子")
        delay(514)

        // Client will be closed by server.
        client.send("close")
        client.await()

        assertTrue(client.isWaiting)
        client.awaitUntilConnected()

        // 在另一个协程上暂停任务
        val pauseJob = client.launch {
            client.pause()
        }

        client.send("我们的同志在困难的时候")
        delay(114)

        client.send("要看到成绩")
        delay(514)

        client.send("要看到光明")
        delay(114)

        client.send("要提高我们的勇气")
        delay(514)

        pauseJob.join()
        assertTrue(client.isPaused)

        client.resume()
        client.awaitUntilConnected()

        // Server will be closed.
        server.stop()
        delay(114)

        assertTrue(client.isWaiting)
        server = createWebSocketServer()
        client.awaitUntilConnected()

        server.stop()
        client.close()
    }
}

private fun Application.module() {
    install(WebSockets)
    routing {
        webSocket(PATH) {
            logger.info { "Client connected!" }
            for (frame in incoming) {
                logger.info { "Received frame: $frame" }
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    if (text == "close") {
                        return@webSocket
                    }
                }
            }
        }
    }
}
