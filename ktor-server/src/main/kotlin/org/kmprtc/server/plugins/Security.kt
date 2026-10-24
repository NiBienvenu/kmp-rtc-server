package org.kmprtc.server.plugins

import org.kmprtc.server.config.JwtConfig
import org.kmprtc.server.services.TokenService
import org.kmprtc.server.models.ApiResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.respond

fun Application.configureSecurity() {

    // Initialisation depuis application.conf
    JwtConfig.init(environment.config)

    install(Authentication) {
        jwt("jwt-auth") {
            realm = JwtConfig.realm

            verifier(JwtConfig.verifier.value)

            validate { credential ->
                // 1. Vérifier que le token n'est pas dans la blacklist (logout)
                val rawToken = request.headers["Authorization"]
                    ?.removePrefix("Bearer ")
                    ?.trim()

                if (rawToken != null && TokenService.isBlacklisted(rawToken)) {
                    return@validate null
                }

                // 2. Vérifier les claims requis
                val userId = credential.payload.getClaim("userId").asString()
                val email  = credential.payload.getClaim("email").asString()

                if (!userId.isNullOrBlank() && !email.isNullOrBlank()) {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }

            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiResponse<Nothing>(false, "Token invalide ou expiré")
                )
            }
        }
    }
}
