package org.kmprtc.server.routes

import org.kmprtc.server.models.ApiResponse
import org.kmprtc.server.models.CallInviteRequest
import org.kmprtc.server.services.CallInviteService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.callRoutes(push: CallInviteService) {
    route("/calls") {
        // POST /calls/invite -> notifie le destinataire via le WebSocket /ws (local, sans Internet).
        post("/invite") {
            val req = call.receive<CallInviteRequest>()
            val kind = req.kind.lowercase()
            if (kind !in setOf("voice", "video")) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiResponse<Unit>(false, "kind invalide"))
            }
            push.sendIncomingCallInvite(
                toUserId = req.calleeUserId,
                target = req.target,
                callerName = req.callerName,
                kind = kind,
            )
            call.respond(HttpStatusCode.OK, ApiResponse<Unit>(true, "Invite envoye"))
        }
    }
}

