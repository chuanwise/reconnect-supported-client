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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import me.him188.kotlin.jvm.blocking.bridge.JvmBlockingBridge
import java.util.concurrent.TimeUnit
import kotlin.jvm.Throws

/**
 * 可自动重连的长连接客户端。
 *
 * @author Chuanwise
 */
interface ReconnectSupportedClient : CoroutineScope {
    val isStarted: Boolean
    val isConnected: Boolean
    val isWaiting: Boolean
    val isPaused: Boolean

    val isClosing: Boolean
    val isClosingOrClosed: Boolean
    val isClosed: Boolean

    /**
     * 启动连接。
     */
    fun start()

    /**
     * 暂停自动重连。
     */
    fun pause()

    /**
     * 恢复自动重连。
     */
    fun resume()

    /**
     * 关闭连接。
     */
    suspend fun close()

    /**
     * 等待连接状态发生变化。
     */
    @JvmBlockingBridge
    @Throws(InterruptedException::class)
    suspend fun await()

    /**
     * 等待连接状态发生变化。
     *
     * @param time 等待时间
     * @param unit 时间单位
     */
    @JvmBlockingBridge
    @Throws(InterruptedException::class)
    suspend fun await(time: Long, unit: TimeUnit = TimeUnit.MILLISECONDS): Boolean

    /**
     * 用于接受服务端发送消息的通道。
     */
    val channel: Channel<String>

    /**
     * 发送消息给服务端。
     *
     * @param string 消息内容
     */
    @JvmBlockingBridge
    suspend fun send(string: String)
}

suspend fun ReconnectSupportedClient.awaitUntilConnected() {
    while (!isConnected) {
        await()
    }
}