package org.kmprtc.server.services

import io.ktor.http.content.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.kmprtc.server.config.DatabaseFactory.dbQuery
import org.kmprtc.server.models.CdrTable
import org.kmprtc.server.models.ConferenceRoomTable
import org.kmprtc.server.models.RecordingItem
import org.kmprtc.server.models.RecordingTable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Enregistrements audio / video stockes sur le volume du conteneur:
 *   <dir>/calls/AAAA/MM/JJ/<uniqueid>.wav     ecrits par Asterisk (MixMonitor)
 *   <dir>/conferences/<salle>/<date>.wav       ecrits par Asterisk (ConfBridge)
 *   <dir>/uploads/AAAA/MM/JJ/<uuid>.<ext>      envoyes par les applications (audio ou video)
 */
class RecordingService(baseDir: String, private val maxUploadBytes: Long) {

    val base: Path = Paths.get(baseDir).toAbsolutePath().normalize()

    private val allowedExt = mapOf(
        "wav" to "audio/wav", "ogg" to "audio/ogg", "opus" to "audio/ogg", "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4", "webm" to "video/webm", "mp4" to "video/mp4", "mkv" to "video/x-matroska",
    )

    /** Resout un chemin relatif en restant obligatoirement dans le dossier des enregistrements. */
    fun resolve(rel: String): File? {
        val p = base.resolve(rel.removePrefix("/")).normalize()
        if (!p.startsWith(base)) return null
        return p.toFile().takeIf { it.isFile }
    }

    /** Asterisk ecrit un chemin absolu dans CDR(recordingfile): on le ramene au dossier de base. */
    private fun relFromAbsolute(abs: String): String? {
        val p = Paths.get(abs).normalize()
        return if (p.startsWith(base)) base.relativize(p).toString() else null
    }

    suspend fun list(userId: String, sipUsername: String?, isAdmin: Boolean): List<RecordingItem> {
        val items = mutableListOf<RecordingItem>()

        // 1. Appels (CDR avec un fichier)
        dbQuery {
            val q = CdrTable.select { CdrTable.recordingfile neq "" }
            if (!isAdmin) {
                if (sipUsername == null) return@dbQuery
                q.andWhere {
                    (CdrTable.src eq sipUsername) or (CdrTable.dst eq sipUsername) or (CdrTable.dst eq "$sipUsername-web")
                }
            }
            q.orderBy(CdrTable.calldate to SortOrder.DESC).limit(500).forEach { row ->
                val rel = relFromAbsolute(row[CdrTable.recordingfile]) ?: return@forEach
                val f = resolve(rel) ?: return@forEach
                if (f.length() <= WAV_HEADER_BYTES) return@forEach   // appel jamais decroche: fichier vide
                items += RecordingItem(
                    id = "call-${row[CdrTable.id]}", source = "call", kind = "audio",
                    fileName = f.name, sizeBytes = f.length(), createdAt = row[CdrTable.calldate].toString(),
                    callUniqueId = row[CdrTable.uniqueid], downloadUrl = "/recordings/call-${row[CdrTable.id]}/file",
                )
            }
        }

        // 2. Conferences (salles dont l'utilisateur est proprietaire, ou toutes pour l'admin)
        val rooms = dbQuery {
            val q = if (isAdmin) ConferenceRoomTable.selectAll()
            else ConferenceRoomTable.select { ConferenceRoomTable.ownerId eq userId }
            q.map { it[ConferenceRoomTable.id] to it[ConferenceRoomTable.roomNumber] }
        }
        for ((roomId, number) in rooms) {
            val dir = base.resolve("conferences").resolve(number).toFile()
            dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.forEach { f ->
                items += RecordingItem(
                    id = "conf-$roomId-${f.name}", source = "conference", kind = "audio",
                    fileName = f.name, sizeBytes = f.length(),
                    createdAt = Instant.ofEpochMilli(f.lastModified()).toString(), roomNumber = number,
                    downloadUrl = "/recordings/conf-$roomId-${f.name}/file",
                )
            }
        }

        // 3. Fichiers envoyes par les applis
        dbQuery {
            val q = if (isAdmin) RecordingTable.selectAll() else RecordingTable.select { RecordingTable.ownerId eq userId }
            q.orderBy(RecordingTable.createdAt to SortOrder.DESC).forEach { row ->
                items += RecordingItem(
                    id = "upload-${row[RecordingTable.id]}", source = "upload", kind = row[RecordingTable.kind],
                    fileName = row[RecordingTable.fileName], sizeBytes = row[RecordingTable.sizeBytes],
                    createdAt = row[RecordingTable.createdAt], callUniqueId = row[RecordingTable.callUniqueId],
                    roomNumber = row[RecordingTable.roomNumber], downloadUrl = "/recordings/upload-${row[RecordingTable.id]}/file",
                )
            }
        }
        return items.sortedByDescending { it.createdAt }
    }

    /** Fichier + type MIME si l'utilisateur y a droit, sinon null. */
    suspend fun fileFor(id: String, userId: String, sipUsername: String?, isAdmin: Boolean): Pair<File, String>? = when {
        id.startsWith("call-") -> dbQuery {
            val row = CdrTable.select { CdrTable.id eq (id.removePrefix("call-").toLongOrNull() ?: -1) }.singleOrNull()
                ?: return@dbQuery null
            val mine = sipUsername != null &&
                (row[CdrTable.src] == sipUsername || row[CdrTable.dst].removeSuffix("-web") == sipUsername)
            if (!isAdmin && !mine) return@dbQuery null
            relFromAbsolute(row[CdrTable.recordingfile])?.let(::resolve)?.let { it to mime(it.name) }
        }
        id.startsWith("conf-") -> {
            val rest = id.removePrefix("conf-")
            val roomId = rest.take(36)
            val fileName = rest.drop(37)
            dbQuery {
                val row = ConferenceRoomTable.select { ConferenceRoomTable.id eq roomId }.singleOrNull() ?: return@dbQuery null
                if (!isAdmin && row[ConferenceRoomTable.ownerId] != userId) return@dbQuery null
                if (fileName.contains('/') || fileName.startsWith(".")) return@dbQuery null
                resolve("conferences/${row[ConferenceRoomTable.roomNumber]}/$fileName")?.let { it to mime(it.name) }
            }
        }
        id.startsWith("upload-") -> dbQuery {
            val row = RecordingTable.select { RecordingTable.id eq id.removePrefix("upload-") }.singleOrNull()
                ?: return@dbQuery null
            if (!isAdmin && row[RecordingTable.ownerId] != userId) return@dbQuery null
            resolve(row[RecordingTable.relPath])?.let { it to row[RecordingTable.mimeType] }
        }
        else -> null
    }

    /** Recoit un fichier multipart (champs: file, kind, callUniqueId?, roomNumber?). */
    suspend fun upload(userId: String, multipart: MultiPartData): RecordingItem {
        var kind = "audio"
        var callUniqueId: String? = null
        var roomNumber: String? = null
        var saved: Triple<String, String, Long>? = null   // relPath, originalName, size
        var mimeType = "application/octet-stream"
        // L'erreur est levee apres avoir lu tout le corps: sinon le client attend sans reponse.
        var rejection: String? = null

        multipart.forEachPart { part ->
            try {
                when (part) {
                    is PartData.FormItem -> when (part.name) {
                        "kind" -> kind = part.value.lowercase()
                        "callUniqueId" -> callUniqueId = part.value.take(150).ifBlank { null }
                        "roomNumber" -> roomNumber = part.value.take(16).ifBlank { null }
                    }
                    is PartData.FileItem -> if (part.name == "file" && saved == null) {
                        val original = (part.originalFileName ?: "recording").substringAfterLast('/').take(200)
                        val ext = original.substringAfterLast('.', "").lowercase()
                        val allowedMime = allowedExt[ext]
                        if (allowedMime == null) {
                            rejection = "Format refuse. Formats acceptes: ${allowedExt.keys.joinToString()}"
                            withContext(Dispatchers.IO) {
                                part.provider().toInputStream().use { it.copyTo(java.io.OutputStream.nullOutputStream()) }
                            }
                            return@forEachPart
                        }
                        mimeType = allowedMime
                        val d = LocalDate.now()
                        val rel = "uploads/%04d/%02d/%02d/%s.%s".format(d.year, d.monthValue, d.dayOfMonth, UUID.randomUUID(), ext)
                        val target = base.resolve(rel)
                        val size = withContext(Dispatchers.IO) {
                            Files.createDirectories(target.parent)
                            var total = 0L
                            part.provider().toInputStream().use { input ->
                                target.toFile().outputStream().use { out ->
                                    val buf = ByteArray(64 * 1024)
                                    while (true) {
                                        val n = input.read(buf)
                                        if (n < 0) break
                                        total += n
                                        if (total > maxUploadBytes) {
                                            rejection = "Fichier trop gros (max ${maxUploadBytes / 1_048_576} Mo)"
                                            continue   // on vide le reste sans l'ecrire
                                        }
                                        out.write(buf, 0, n)
                                    }
                                }
                            }
                            if (rejection != null) Files.deleteIfExists(target)
                            total
                        }
                        if (rejection == null) saved = Triple(rel, original, size)
                    }
                    else -> Unit
                }
            } finally {
                part.dispose()
            }
        }

        rejection?.let { throw IllegalArgumentException(it) }
        val (rel, original, size) = saved ?: throw IllegalArgumentException("Champ 'file' manquant")
        require(kind in setOf("audio", "video")) { "kind doit etre audio ou video" }
        val id = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        dbQuery {
            RecordingTable.insert {
                it[RecordingTable.id] = id
                it[ownerId] = userId
                it[RecordingTable.kind] = kind
                it[RecordingTable.callUniqueId] = callUniqueId
                it[RecordingTable.roomNumber] = roomNumber
                it[fileName] = original
                it[relPath] = rel
                it[RecordingTable.mimeType] = mimeType
                it[sizeBytes] = size
                it[createdAt] = now
            }
        }
        return RecordingItem(
            id = "upload-$id", source = "upload", kind = kind, fileName = original, sizeBytes = size,
            createdAt = now, callUniqueId = callUniqueId, roomNumber = roomNumber,
            downloadUrl = "/recordings/upload-$id/file",
        )
    }

    suspend fun deleteUpload(id: String, userId: String, isAdmin: Boolean): Boolean {
        val row = dbQuery { RecordingTable.select { RecordingTable.id eq id }.singleOrNull() } ?: return false
        if (!isAdmin && row[RecordingTable.ownerId] != userId) throw IllegalStateException("Acces refuse")
        resolve(row[RecordingTable.relPath])?.delete()
        return dbQuery { RecordingTable.deleteWhere { RecordingTable.id eq id } > 0 }
    }

    private companion object {
        const val WAV_HEADER_BYTES = 44L
    }

    private fun mime(name: String) = allowedExt[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"
}
