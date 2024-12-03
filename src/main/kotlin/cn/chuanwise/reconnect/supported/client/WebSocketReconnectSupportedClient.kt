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

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.ClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.HttpMethod
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.EOFException
import java.net.ConnectException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 可自动重连的 WebSocket 客户端。
 *
 * @param host WebSocket 服务器的主机地址。
 * @param port WebSocket 服务器的端口。
 * @param path WebSocket 服务器的路径。
 * @param reconnectInterval 重连间隔。
 * @param httpMethod WebSocket 连接的 HTTP 方法。
 * @param httpClient 用于连接的 HttpClient。
 * @param manageHttpClient 是否由本类管理 HttpClient 的生命周期。
 * @param logger 日志记录器。
 * @param maxConnectAttempts 最大连接尝试次数。
 * @param parentJob 父协程任务。
 * @param parentCoroutineContext 父协程上下文。
 * @author Chuanwise
 * @see ReconnectSupportedClient
 */
class WebSocketReconnectSupportedClient(
    private val host: String? = null,
    private val port: Int? = null,
    private val path: String? = null,
    private val reconnectInterval: Duration = 5.seconds,
    private val httpMethod: HttpMethod = HttpMethod.Get,
    httpClient: HttpClient? = null,
    manageHttpClient: Boolean? = null,
    private val logger: KLogger = KotlinLogging.logger { },
    private val maxConnectAttempts: Int? = null,
    parentJob: Job? = null,
    parentCoroutineContext: CoroutineContext = Dispatchers.IO
) : ReconnectSupportedClient {
    private val job = SupervisorJob(parentJob)
    private val scope = CoroutineScope(job + parentCoroutineContext)
    override val coroutineContext: CoroutineContext get() = scope.coroutineContext

    private val lock = ReentrantReadWriteLock()
    private val stateUpdateCondition = lock.writeLock().newCondition()

    private val address = "$host:$port"

    private val manageHttpClient = manageHttpClient ?: (httpClient == null)
    private val httpClient = httpClient ?: HttpClient { install(WebSockets) }

    private enum class State {
        CREATED,

        CONNECTING,
        CONNECTED,
        WAITING,
        PAUSED,

        CLOSING,
        CLOSED
    }

    private var stateNoLock = State.CREATED
    private val state: State
        get() = lock.read { stateNoLock }

    private var channelNoLock: Channel<String>? = null

    override val isStarted: Boolean
        get() = lock.read { stateNoLock != State.CREATED && stateNoLock != State.CLOSED && stateNoLock != State.CLOSING }

    override val isConnected: Boolean get() = state == State.CONNECTED
    override val isWaiting: Boolean get() = state == State.WAITING
    override val isPaused: Boolean get() = state == State.PAUSED

    init {
        val maxConnectAttemptsLocal = maxConnectAttempts
        require(maxConnectAttemptsLocal == null || maxConnectAttemptsLocal > 0) {
            "maxConnectAttempts must be positive, but got $maxConnectAttemptsLocal."
        }
    }

    override fun start() {
        lock.write {
            stateNoLock = when (stateNoLock) {
                State.CREATED -> State.CONNECTING
                else -> error("Cannot start when the state is $stateNoLock.")
            }
            stateUpdateCondition.signalAll()
            setConnectingJobNoLock()
        }
    }

    override val isClosing: Boolean get() = state == State.CLOSING
    private val isClosingOrClosedNoLock: Boolean get() = stateNoLock == State.CLOSING || stateNoLock == State.CLOSED
    override val isClosingOrClosed: Boolean get() = lock.read { isClosingOrClosedNoLock }
    override val isClosed: Boolean get() = state == State.CLOSED

    // WebSocket 会话，用于在其他地方调用 send 和 close 等函数。
    private var sessionNoLock: ClientWebSocketSession? = null
    private var connectingJobNoLock: Job? = null

    private fun setConnectingJobNoLock() {
        connectingJobNoLock?.cancel()
        connectingJobNoLock = createConnectingJob()
    }

    private fun createConnectingJob(): Job = launch {
        var attempt = 1
        while (true) {
            lock.write {
                stateNoLock = when (stateNoLock) {
                    // CONNECTING: 初次连接时，才会在连接尚未开始时进入 CONNECTING 状态。
                    // WAITING: 最近一次重连等待结束，开始连接。
                    State.CONNECTING, State.WAITING -> State.CONNECTING

                    // PAUSED: 其他地方调用了 pause()，暂停连接。
                    // CLOSING, CLOSED: 其他地方调用了 close()，停止连接。
                    State.PAUSED, State.CLOSING, State.CLOSED -> return@launch

                    else -> error("Unexpected state: $stateNoLock.")
                }

                stateUpdateCondition.signalAll()
            }

            // 显示连接日志。
            if (maxConnectAttempts == null) {
                logger.debug { "Connecting to $address ($attempt)" }
            } else {
                logger.debug { "Connecting to $address ($attempt / $maxConnectAttempts)" }
            }

            try {
                httpClient.webSocket(
                    method = httpMethod,
                    host = host,
                    port = port,
                    path = path
                ) {
                    // 执行到此处说明连接建立。
                    // 避免使用 channelNoLock 时总是需要判断是否为空。
                    val channelLocal: Channel<String>

                    lock.write {
                        stateNoLock = when (stateNoLock) {
                            State.CONNECTING -> State.CONNECTED

                            // PAUSED: 连接建立期间其他地方调用了 pause()，不希望接下来连接，应当尽快断开连接。
                            // CLOSING, CLOSED: 连接建立期间其他地方调用了 close()，尽快断开连接。
                            State.PAUSED, State.CLOSING, State.CLOSED -> return@webSocket

                            else -> error("Unexpected state: $stateNoLock.")
                        }

                        sessionNoLock = this

                        channelLocal = Channel(Channel.UNLIMITED)
                        channelNoLock = channelLocal
                        attempt = 1

                        stateUpdateCondition.signalAll()
                    }

                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            channelLocal.send(frame.readText())
                        } else {
                            logger.error { "Unexpected frame: $frame." }
                        }
                    }

                    // 执行到此处说明输入管道被关闭。
                    lock.write {
                        sessionNoLock = null
                        channelNoLock = null

                        stateNoLock = when (stateNoLock) {
                            State.CONNECTED -> State.WAITING

                            // PAUSED: 连接期间其他地方调用了 pause()，即在连接断开后不希望自动重连。
                            State.PAUSED -> State.PAUSED

                            // CLOSING, CLOSED: 连接期间其他地方调用了 close()，尽快断开连接。
                            State.CLOSING, State.CLOSED -> return@webSocket

                            else -> error("Unexpected state: $stateNoLock.")
                        }

                        // 此处不调用 stateUpdateCondition.signalAll() 的原因是此时的 WAITING 只是一个暂时状态。
                    }
                }
            } catch (e: Throwable) {
                lock.write {
                    sessionNoLock = null
                    channelNoLock = null

                    stateNoLock = when (stateNoLock) {
                        // CONNECTED: 连接被对方关闭。
                        // CONNECTING: 连接失败或对方关闭服务器。
                        // WAITING: 连接状态修改后关闭服务器。
                        State.CONNECTING, State.CONNECTED, State.WAITING -> State.WAITING

                        // PAUSED: 连接期间其他地方调用了 pause()，即在连接失败后不希望自动重连。
                        State.PAUSED -> State.PAUSED

                        // CLOSING, CLOSED: 连接期间其他地方调用了 close()，尽快断开连接。
                        State.CLOSING, State.CLOSED -> return@launch

                        else -> error("Unexpected state: $stateNoLock.")
                    }

                    // 此处不调用 stateUpdateCondition.signalAll() 的原因是此时的 WAITING 只是一个暂时状态。
                }

                when (e) {
                    is CancellationException -> logger.debug { "Connection closed by $address." }
                    is EOFException -> logger.debug { "Remote server closed." }
                    is ConnectException -> logger.error { "Fail to connect to $address." }
                    else -> logger.error(e) { "Exception occurred from connection." }
                }
            }

            lock.write {
                when (stateNoLock) {
                    State.WAITING -> {
                        // 检查尝试次数是否超过限制，如未设置上限则无需检查。
                        val maxConnectAttemptsLocal = maxConnectAttempts
                        if (maxConnectAttemptsLocal != null && attempt >= maxConnectAttemptsLocal) {
                            stateNoLock = State.PAUSED
                            return@launch
                        }
                    }

                    // PAUSED: 结束自动重连任务。下次调用 resume() 时会再次启动一个任务。
                    // CLOSING, CLOSED: 断开连接期间其他地方调用了 close()，尽快结束连接任务。
                    State.PAUSED, State.CLOSING, State.CLOSED -> return@launch

                    else -> error("Unexpected state: $stateNoLock.")
                }

                // 此时的 WAITING 才表示真正决定等待一段时间。
                stateUpdateCondition.signalAll()

                attempt++

                // 等待一段时间后继续连接。
                // 中途若状态改变，只可能是关闭、暂停或者继续连接。
                stateUpdateCondition.await(reconnectInterval.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            }
        }
    }

    override fun pause() {
        lock.write {
            stateNoLock = when (stateNoLock) {
                State.CREATED -> error("Client not started.")
                State.CLOSING, State.CLOSED -> error("Client is closing or closed.")

                // CONNECTING, CONNECTED: 此次连接断开后暂停重连等待。
                // WAITING: 此次重连等待结束后暂停重连等待（需要 stateUpdateCondition.signalAll() 提醒连接任务）
                State.CONNECTING, State.CONNECTED, State.WAITING -> State.PAUSED

                // PAUSED: 无需进行任何操作。
                State.PAUSED -> return
            }

            stateUpdateCondition.signalAll()
        }
    }

    override fun resume() {
        lock.write {
            stateNoLock = when (stateNoLock) {
                State.CREATED -> error("Client not started.")
                State.CLOSING, State.CLOSED -> error("Client is closing or closed.")

                // CONNECTING, CONNECTED, PAUSED: 本来就正在连接中，无需任何操作。
                State.CONNECTING, State.CONNECTED, State.WAITING -> return

                // PAUSED: 重新开始连接，需要创建新的连接任务。
                State.PAUSED -> State.CONNECTING
            }

            setConnectingJobNoLock()
            stateUpdateCondition.signalAll()
        }
    }

    override suspend fun await() {
        lock.write {
            stateUpdateCondition.await()
        }
    }

    override suspend fun await(time: Long, unit: TimeUnit) : Boolean {
        return lock.write {
            stateUpdateCondition.await(time, unit)
        }
    }

    override val channel: Channel<String>
        get() = lock.read { channelNoLock ?: error("Connection not established.") }

    override suspend fun send(string: String) {
        val session = sessionNoLock ?: error("Connection not established.")
        logger.trace { "Send: $string." }

        session.send(Frame.Text(string))
    }

    override suspend fun close() {
        lock.write {
            stateNoLock = when (stateNoLock) {
                State.CLOSED -> error("Client already closed.")

                // CREATED: 还没 start() 就 close() 了，真的是太逊了。
                // CONNECTING: 连接中就关闭了。此处只需要修改状态，等待连接建立成功或失败时会检查状态的。
                // CONNECTED: 此时连接已经建立，需要关闭连接（后面会关闭）。
                // WAITING: 正在等待是否需要重连，只需要更新状态，连接任务就会醒来并立刻检查状态。
                // PAUSED: 连接任务暂停，不需要做什么操作。
                State.CREATED, State.CONNECTING, State.CONNECTED, State.WAITING, State.PAUSED -> State.CLOSING

                else -> error("Unexpected state: $stateNoLock.")
            }

            stateUpdateCondition.signalAll()
        }

        sessionNoLock?.close()
        connectingJobNoLock?.cancel()
        job.cancelAndJoin()

        if (manageHttpClient) {
            httpClient.close()
        }

        lock.write {
            stateNoLock = State.CLOSED
        }
    }
}