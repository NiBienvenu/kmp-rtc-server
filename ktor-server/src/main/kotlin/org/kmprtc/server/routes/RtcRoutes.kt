package org.kmprtc.server.routes

import org.kmprtc.server.models.ApiResponse
import org.kmprtc.server.models.CreateRoomRequest
import org.kmprtc.server.repositories.CdrRepository
import org.kmprtc.server.repositories.RoomRepository
import org.kmprtc.server.repositories.VoipRepository
import org.kmprtc.server.services.AsteriskCli
import org.kmprtc.server.services.IceService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

private fun ApplicationCall.userId(): String =
    principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()

private fun ApplicationCall.isAdmin(): Boolean =
    principal<JWTPrincipal>()?.payload?.getClaim("role")?.asString() == "admin"

/** Salles de conference (ConfBridge). A appeler dans un bloc authenticate("jwt-auth"). */
fun Route.roomRoutes(rooms: RoomRepository) {
    route("/rooms") {
        // GET /rooms
        get {
            call.respond(ApiResponse(true, data = rooms.list(call.userId(), call.isAdmin())))
        }

        // POST /rooms  {name, pin?, maxParticipants, video}
        post {
            val req = call.receive<CreateRoomRequest>()
            require(req.name.isNotBlank()) { "name requis" }
            require(req.maxParticipants in 2..200) { "maxParticipants doit etre entre 2 et 200" }
            require(req.pin == null || req.pin.matches(Regex("\\d{0,16}"))) { "pin: chiffres seulement" }
            val room = rooms.create(call.userId(), req)
            call.respond(HttpStatusCode.Created, ApiResponse(true, "Salle creee", room))
        }

        // GET /rooms/{id}
        get("/{id}") {
            val id = call.parameters["id"]!!
            val room = rooms.findById(id, call.userId(), call.isAdmin())
                ?: throw NoSuchElementException("Salle introuvable")
            call.respond(ApiResponse(true, data = room))
        }

        // DELETE /rooms/{id}  (proprietaire ou admin)
        delete("/{id}") {
            val id = call.parameters["id"]!!
            val owner = rooms.ownerOf(id) ?: throw NoSuchElementException("Salle introuvable")
            if (owner != call.userId() && !call.isAdmin()) {
                return@delete call.respond(HttpStatusCode.Forbidden, ApiResponse<Unit>(false, "Acces refuse"))
            }
            rooms.deactivate(id)
            call.respond(ApiResponse<Unit>(true, "Salle fermee"))
        }
    }
}

/** Journal d'appels (CDR) et statistiques. A appeler dans un bloc authenticate("jwt-auth"). */
fun Route.cdrRoutes(cdr: CdrRepository, voipRepository: VoipRepository) {
    // GET /calls/history?limit=50  -> appels de l'utilisateur connecte
    get("/calls/history") {
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 50
        val sipUser = voipRepository.findSipUsername(call.userId())
            ?: return@get call.respond(ApiResponse(true, data = emptyList<Unit>()))
        call.respond(ApiResponse(true, data = cdr.historyFor(sipUser, limit)))
    }

    // GET /admin/cdr?limit=100  et  GET /admin/stats?days=30  (admin)
    route("/admin") {
        get("/cdr") {
            if (!call.isAdmin()) return@get call.respond(HttpStatusCode.Forbidden, ApiResponse<Unit>(false, "Reserve aux administrateurs"))
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 100
            call.respond(ApiResponse(true, data = cdr.recent(limit)))
        }
        get("/stats") {
            if (!call.isAdmin()) return@get call.respond(HttpStatusCode.Forbidden, ApiResponse<Unit>(false, "Reserve aux administrateurs"))
            val days = call.request.queryParameters["days"]?.toIntOrNull()?.coerceIn(1, 366) ?: 30
            call.respond(ApiResponse(true, data = cdr.stats(days)))
        }
    }
}

/** Serveurs ICE (STUN/TURN) au format WebRTC. A appeler dans un bloc authenticate("jwt-auth"). */
fun Route.iceRoutes(ice: IceService) {
    get("/rtc/ice-servers") {
        call.respond(ApiResponse(true, data = ice.iceServers(call.userId())))
    }
}

/** Etat SIP en direct (admin): contacts enregistres et canaux actifs. */
fun Route.asteriskAdminRoutes(cli: AsteriskCli) {
    route("/admin/asterisk") {
        get("/{what}") {
            if (!call.isAdmin()) return@get call.respond(HttpStatusCode.Forbidden, ApiResponse<Unit>(false, "Reserve aux administrateurs"))
            // Liste blanche: aucune commande libre n'est transmise a Asterisk.
            val command = when (call.parameters["what"]) {
                "contacts" -> "pjsip show contacts"
                "endpoints" -> "pjsip show endpoints"
                "channels" -> "core show channels concise"
                "conferences" -> "confbridge list"
                "uptime" -> "core show uptime"
                else -> throw NoSuchElementException("Inconnu. Choix: contacts, endpoints, channels, conferences, uptime")
            }
            val res = cli.run(command)
            call.respond(ApiResponse(res.exitCode == 0, data = res.output))
        }
    }
}
