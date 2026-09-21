package com.securechat.app.data.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Maintains a live connection to the relay server so incoming messages are pushed instantly
 * instead of waiting for the next poll. The push payload itself is not trusted or parsed here -
 * it's only a "something changed" nudge; [onMessagePushed] should re-fetch and decrypt via the
 * normal REST endpoint. Reconnects with capped exponential backoff, since mobile networks drop
 * idle sockets constantly; callers should still poll periodically as a fallback.
 */
class WebSocketClient(
    private val client: OkHttpClient,
    private val wsBaseUrl: String,
    private val scope: CoroutineScope,
    private val onMessagePushed: () -> Unit,
) {
    private var socket: WebSocket? = null
    private var stopped = true
    private var attempt = 0

    fun connect(userId: String) {
        stopped = false
        attempt = 0
        openSocket(userId)
    }

    fun disconnect() {
        stopped = true
        socket?.close(1000, "client closing")
        socket = null
    }

    private fun openSocket(userId: String) {
        val request = Request.Builder().url("$wsBaseUrl/v1/ws/$userId").build()
        socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    attempt = 0
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    onMessagePushed()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    scheduleReconnect(userId)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    scheduleReconnect(userId)
                }
            },
        )
    }

    private fun scheduleReconnect(userId: String) {
        if (stopped) return
        val backoffMillis = minOf(30_000L, 1_000L * (1L shl minOf(attempt, 5)))
        attempt += 1
        scope.launch {
            delay(backoffMillis)
            if (!stopped && scope.isActive) openSocket(userId)
        }
    }
}
