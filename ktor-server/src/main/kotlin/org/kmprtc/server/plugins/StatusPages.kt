package org.kmprtc.server.plugins

import org.kmprtc.server.models.ApiResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*

fun Application.configureStatusPages() {
    install(StatusPages) {

        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiResponse<Nothing>(false, cause.message ?: "Requête invalide")
            )
        }

        exception<NoSuchElementException> { call, cause ->
            call.respond(
                HttpStatusCode.NotFound,
                ApiResponse<Nothing>(false, cause.message ?: "Ressource introuvable")
            )
        }

        exception<IllegalStateException> { call, cause ->
            call.respond(
                HttpStatusCode.Conflict,
                ApiResponse<Nothing>(false, cause.message ?: "Conflit")
            )
        }

        exception<Throwable> { call, cause ->
            call.application.log.error("Erreur non gérée", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiResponse<Nothing>(false, "Erreur serveur interne")
            )
        }
    }
}
