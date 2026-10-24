package org.kmprtc.server.routes

import org.kmprtc.server.models.ApiResponse
import org.kmprtc.server.models.UpdateUserRequest
import org.kmprtc.server.services.UserService
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.userRoutes(userService: UserService) {

    authenticate("jwt-auth") {

        route("/users") {

            // GET /users — liste tous les utilisateurs (admin only recommandé)
            get {
                val users = userService.getAll()
                call.respond(HttpStatusCode.OK, ApiResponse(true, data = users))
            }

            // GET /users/{id}
            get("/{id}") {
                val id = call.parameters["id"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse<Nothing>(false, "ID manquant")
                    )

                val user = userService.getById(id)
                call.respond(HttpStatusCode.OK, ApiResponse(true, data = user))
            }

            // GET /users/me — profil de l'utilisateur connecté
            get("/me") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("userId").asString()

                val user = userService.getById(userId)
                call.respond(HttpStatusCode.OK, ApiResponse(true, data = user))
            }

            // PUT /users/{id}
            put("/{id}") {
                val id = call.parameters["id"]
                    ?: return@put call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse<Nothing>(false, "ID manquant")
                    )

                // Vérifier que l'utilisateur modifie son propre compte (ou est admin)
                val principal = call.principal<JWTPrincipal>()!!
                val callerId  = principal.payload.getClaim("userId").asString()
                val callerRole = principal.payload.getClaim("role").asString()

                if (callerId != id && callerRole != "admin") {
                    return@put call.respond(
                        HttpStatusCode.Forbidden,
                        ApiResponse<Nothing>(false, "Accès refusé")
                    )
                }

                val request = call.receive<UpdateUserRequest>()
                val updated = userService.update(id, request)
                call.respond(HttpStatusCode.OK, ApiResponse(true, "Utilisateur mis à jour", updated))
            }

            // DELETE /users/{id}
            delete("/{id}") {
                val id = call.parameters["id"]
                    ?: return@delete call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse<Nothing>(false, "ID manquant")
                    )

                // Seul l'admin peut supprimer
                val principal  = call.principal<JWTPrincipal>()!!
                val callerRole = principal.payload.getClaim("role").asString()

                if (callerRole != "admin") {
                    return@delete call.respond(
                        HttpStatusCode.Forbidden,
                        ApiResponse<Nothing>(false, "Réservé aux administrateurs")
                    )
                }

                userService.delete(id)
                call.respond(HttpStatusCode.OK, ApiResponse<Nothing>(true, "Utilisateur supprimé"))
            }
        }
    }
}
