package org.kmprtc.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.kmprtc.server.models.ApiResponse
import org.kmprtc.server.repositories.VoipRepository
import org.kmprtc.server.services.RecordingService

/**
 * Enregistrements audio/video. A appeler dans un bloc authenticate("jwt-auth").
 *  GET    /recordings              liste (appels, conferences, fichiers envoyes)
 *  GET    /recordings/{id}/file    lecture / telechargement (supporte Range: lecture video)
 *  POST   /recordings/upload       envoi multipart: file, kind=audio|video, callUniqueId?, roomNumber?
 *  DELETE /recordings/{id}         supprime un fichier envoye (upload-...)
 */
fun Route.recordingRoutes(recordings: RecordingService, voipRepository: VoipRepository) {
    fun ApplicationCall.uid() = principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
    fun ApplicationCall.admin() = principal<JWTPrincipal>()?.payload?.getClaim("role")?.asString() == "admin"

    route("/recordings") {
        get {
            val uid = call.uid()
            call.respond(ApiResponse(true, data = recordings.list(uid, voipRepository.findSipUsername(uid), call.admin())))
        }

        get("/{id}/file") {
            val uid = call.uid()
            val (file, mime) = recordings.fileFor(call.parameters["id"]!!, uid, voipRepository.findSipUsername(uid), call.admin())
                ?: throw NoSuchElementException("Enregistrement introuvable")
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Inline.withParameter(ContentDisposition.Parameters.FileName, file.name).toString(),
            )
            call.response.header(HttpHeaders.ContentType, mime)
            call.respondFile(file)
        }

        post("/upload") {
            val item = recordings.upload(call.uid(), call.receiveMultipart(formFieldLimit = Long.MAX_VALUE))
            call.respond(HttpStatusCode.Created, ApiResponse(true, "Enregistrement stocke", item))
        }

        delete("/{id}") {
            val id = call.parameters["id"]!!
            require(id.startsWith("upload-")) { "Seuls les fichiers envoyes (upload-...) peuvent etre supprimes par l'API" }
            if (!recordings.deleteUpload(id.removePrefix("upload-"), call.uid(), call.admin())) {
                throw NoSuchElementException("Enregistrement introuvable")
            }
            call.respond(ApiResponse<Unit>(true, "Supprime"))
        }
    }
}
