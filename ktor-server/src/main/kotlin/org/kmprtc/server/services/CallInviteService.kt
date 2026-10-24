package org.kmprtc.server.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.kmprtc.server.realtime.RealtimeHub

/**
 * Notification "appel entrant" envoyee par le WebSocket temps reel (/ws) de l'API.
 * 100 % local: aucun service externe (FCM/APNs), fonctionne sur un serveur sans Internet.
 * L'application doit garder la connexion /ws ouverte (service au premier plan sur Android).
 */
class CallInviteService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun sendIncomingCallInvite(toUserId: String, target: String, callerName: String, kind: String) {
        val payload = buildJsonObject {
            put("type", "incoming_call")
            put("target", target)
            put("callerName", callerName)
            put("kind", kind)
        }.toString()
        scope.launch { runCatching { RealtimeHub.sendToUser(toUserId, payload) } }
    }
}
