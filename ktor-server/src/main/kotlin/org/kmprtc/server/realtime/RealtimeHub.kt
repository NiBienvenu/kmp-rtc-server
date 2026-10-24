package org.kmprtc.server.realtime

import io.ktor.server.websocket.*
import io.ktor.websocket.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Hub temps réel minimal (présence, signaux d'appel, monitoring QoE, etc.).
 *
 * Pour la production/scalabilité:
 * - remplacer par un bus Redis (pub/sub) et un store de présence,
 * - sticky sessions ou un gateway WS (Nginx) + backplane.
 */
object RealtimeHub {
    private val sessionsByUser = ConcurrentHashMap<String, CopyOnWriteArraySet<DefaultWebSocketServerSession>>()

    fun add(userId: String, session: DefaultWebSocketServerSession) {
        sessionsByUser.computeIfAbsent(userId) { CopyOnWriteArraySet() }.add(session)
    }

    fun remove(userId: String, session: DefaultWebSocketServerSession) {
        sessionsByUser[userId]?.remove(session)
        if (sessionsByUser[userId]?.isEmpty() == true) sessionsByUser.remove(userId)
    }

    fun connectedUsers(): Int = sessionsByUser.size

    suspend fun sendToUser(userId: String, payload: String) {
        sessionsByUser[userId]?.forEach { it.send(Frame.Text(payload)) }
    }
}

