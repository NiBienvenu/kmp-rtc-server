package org.kmprtc.server.routes

import org.kmprtc.server.models.ApiResponse
import org.kmprtc.server.models.LoginRequest
import org.kmprtc.server.models.RegisterRequest
import org.kmprtc.server.services.AuthService
import org.kmprtc.server.services.VoipService
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.authRoutes(authService: AuthService, voipService: VoipService) {

    // POST /auth/register
    post("/register") {
        val request = call.receive<RegisterRequest>()

        if (request.email.isBlank() || request.password.isBlank() || request.displayName.isBlank()) {
            return@post call.respond(
                HttpStatusCode.BadRequest,
                ApiResponse<Nothing>(false, "Champs requis manquants")
            )
        }
        if (request.password.length < 6) {
            return@post call.respond(
                HttpStatusCode.BadRequest,
                ApiResponse<Nothing>(false, "Mot de passe trop court (min 6 caractères)")
            )
        }

        val user = authService.register(request)

        // Provision SIP at account creation time so every new app account has a SIP identity in Asterisk.
        runCatching { voipService.getSipAccount(user.id) }
            .onFailure { call.application.environment.log.warn("SIP provisioning failed for new userId={}: {}", user.id, it.message) }

        call.respond(
            HttpStatusCode.Created,
            ApiResponse(true, "Compte créé avec succès", user)
        )
    }

    // POST /auth/login
    post("/login") {
        val request = call.receive<LoginRequest>()

        val response = authService.login(request)

        // Ensure SIP exists on login too (covers cases where the client doesn't call /sip/account after login).
        runCatching { voipService.getSipAccount(response.user.id) }
            .onFailure { call.application.environment.log.warn("SIP provisioning failed on login userId={}: {}", response.user.id, it.message) }

        call.respond(HttpStatusCode.OK, ApiResponse(true, "Connexion réussie", response))
    }

    // POST /auth/logout — route protégée par JWT
    authenticate("jwt-auth") {
        post("/logout") {
            val principal = call.principal<JWTPrincipal>()
                ?: return@post call.respond(HttpStatusCode.Unauthorized)

            val rawToken = call.request.headers["Authorization"]
                ?.removePrefix("Bearer ")
                ?.trim()
                ?: return@post call.respond(HttpStatusCode.BadRequest)

            authService.logout(rawToken)
            call.respond(HttpStatusCode.OK, ApiResponse<Nothing>(true, "Déconnexion réussie"))
        }
    }
}
