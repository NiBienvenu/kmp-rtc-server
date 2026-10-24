package org.kmprtc.server.routes

import org.kmprtc.server.models.ApiResponse
import org.kmprtc.server.models.DeviceTokenUpsertRequest
import org.kmprtc.server.services.VoipService
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.voipRoutes(voipService: VoipService) {
    authenticate("jwt-auth") {

        // POST /devices/tokens  -> enregistre token FCM / APNs / VoIP
        route("/devices") {
            post("/tokens") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("userId").asString()
                val request = call.receive<DeviceTokenUpsertRequest>()
                voipService.upsertDeviceToken(userId, request)
                call.respond(HttpStatusCode.OK, ApiResponse<Unit>(true, "Token enregistré"))
            }
        }

        // GET /sip/account -> retourne les identifiants SIP pour l'app
        route("/sip") {
            get("/account") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("userId").asString()
                val account = voipService.getSipAccount(userId)
                call.respond(HttpStatusCode.OK, ApiResponse(true, data = account))
            }

            // GET /sip/peer/{userId} -> retourne l'identité SIP (sans password) pour composer un appel
            get("/peer/{userId}") {
                val id = call.parameters["userId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ApiResponse<Unit>(false, "userId manquant"))
                val peer = voipService.getSipPeer(id)
                call.respond(HttpStatusCode.OK, ApiResponse(true, data = peer))
            }
        }
    }
}
