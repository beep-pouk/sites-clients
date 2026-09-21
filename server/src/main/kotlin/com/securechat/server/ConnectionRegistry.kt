package com.securechat.server

import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.websocket.Frame
import java.util.concurrent.ConcurrentHashMap

/** Tracks which users currently have a live WebSocket connection, for instant message push. */
class ConnectionRegistry {
    private val sessionsByUser = ConcurrentHashMap<String, MutableSet<WebSocketServerSession>>()

    fun register(userId: String, session: WebSocketServerSession) {
        sessionsByUser.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }.add(session)
    }

    fun unregister(userId: String, session: WebSocketServerSession) {
        sessionsByUser[userId]?.remove(session)
    }

    /** Best-effort push; a client with no open socket simply picks the message up on next poll. */
    suspend fun push(userId: String, json: String) {
        val sessions = sessionsByUser[userId] ?: return
        for (session in sessions.toList()) {
            runCatching { session.send(Frame.Text(json)) }
        }
    }
}
