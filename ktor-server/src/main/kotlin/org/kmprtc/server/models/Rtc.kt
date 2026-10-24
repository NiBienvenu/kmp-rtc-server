package org.kmprtc.server.models

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

// ─── Salles de conference ─────────────────────────────────────────────────────
// Cette table est aussi lue par Asterisk (func_odbc, voir docker/asterisk/func_odbc.conf).
// Son schema est aussi cree par docker/mariadb/init/01-schema.sql: garder les deux identiques.

object ConferenceRoomTable : Table("conference_rooms") {
    val id              = varchar("id", 36)
    val roomNumber      = varchar("room_number", 16).uniqueIndex()
    val name            = varchar("name", 100)
    val pin             = varchar("pin", 16).nullable()
    val maxParticipants = integer("max_participants").default(20)
    val video           = bool("video").default(true)
    val ownerId         = varchar("owner_id", 36)
    val active          = bool("active").default(true)
    val createdAt       = varchar("created_at", 50).default("")

    override val primaryKey = PrimaryKey(id)
}

// ─── CDR (Call Detail Records) ────────────────────────────────────────────────
// Ecrite par Asterisk (cdr_adaptive_odbc). Ktor ne la cree pas et ne fait que la lire.

object CdrTable : Table("cdr") {
    val id          = long("id")
    val calldate    = datetime("calldate")
    val clid        = varchar("clid", 80)
    val src         = varchar("src", 80)
    val dst         = varchar("dst", 80)
    val dcontext    = varchar("dcontext", 80)
    val channel     = varchar("channel", 80)
    val dstchannel  = varchar("dstchannel", 80)
    val lastapp     = varchar("lastapp", 80)
    val duration    = integer("duration")
    val billsec     = integer("billsec")
    val disposition = varchar("disposition", 45)
    val uniqueid    = varchar("uniqueid", 150)
    val linkedid    = varchar("linkedid", 150)
    val recordingfile = varchar("recordingfile", 255)

    override val primaryKey = PrimaryKey(id)
}

// ─── Enregistrements envoyes par les applications (audio / video) ─────────────
// Les enregistrements faits par Asterisk sont dans le CDR (colonne recordingfile)
// ou dans recordings/conferences/<salle>/ (conferences).

object RecordingTable : Table("recordings") {
    val id           = varchar("id", 36)
    val ownerId      = varchar("owner_id", 36).index()
    val kind         = varchar("kind", 16)                      // audio | video
    val callUniqueId = varchar("call_uniqueid", 150).nullable() // lien avec le CDR
    val roomNumber   = varchar("room_number", 16).nullable()
    val fileName     = varchar("file_name", 255)
    val relPath      = varchar("rel_path", 512)
    val mimeType     = varchar("mime_type", 100)
    val sizeBytes    = long("size_bytes")
    val createdAt    = varchar("created_at", 50)

    override val primaryKey = PrimaryKey(id)
}

// ─── Webhooks recus ───────────────────────────────────────────────────────────

object WebhookEventTable : Table("webhook_events") {
    val id         = varchar("id", 36)
    val sourceName = varchar("source", 64)
    val eventType  = varchar("event_type", 128).default("")
    val payload    = text("payload")
    val receivedAt = varchar("received_at", 50)

    override val primaryKey = PrimaryKey(id)
}

// ─── DTOs ─────────────────────────────────────────────────────────────────────

@Serializable
data class ConferenceRoom(
    val id: String,
    val roomNumber: String,
    val name: String,
    val hasPin: Boolean,
    val pin: String? = null,          // renvoye seulement au proprietaire / admin
    val maxParticipants: Int,
    val video: Boolean,
    val ownerId: String,
    val active: Boolean,
    val createdAt: String,
    val sipUri: String,               // ex: sip:81234@rtc.example.com
)

@Serializable
data class CreateRoomRequest(
    val name: String,
    val pin: String? = null,
    val maxParticipants: Int = 20,
    val video: Boolean = true,
)

@Serializable
data class CallRecord(
    val id: Long,
    val calldate: String,
    val src: String,
    val dst: String,
    val direction: String,            // "outgoing" | "incoming" | "conference" | "other"
    val duration: Int,
    val billsec: Int,
    val disposition: String,
    val uniqueid: String,
)

@Serializable
data class RecordingItem(
    val id: String,                 // ex: "call-123", "upload-<uuid>", "conf-<roomId>-<fichier>"
    val source: String,             // call | conference | upload
    val kind: String,               // audio | video
    val fileName: String,
    val sizeBytes: Long,
    val createdAt: String,
    val callUniqueId: String? = null,
    val roomNumber: String? = null,
    val downloadUrl: String,
)

@Serializable
data class CallStats(
    val totalCalls: Long,
    val answeredCalls: Long,
    val missedCalls: Long,
    val totalBillsec: Long,
    val averageBillsec: Double,
    val perDay: List<DailyCallStats>,
)

@Serializable
data class DailyCallStats(val day: String, val calls: Long, val answered: Long, val billsec: Long)

@Serializable
data class IceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

@Serializable
data class IceServersResponse(val iceServers: List<IceServer>, val ttlSeconds: Long)

@Serializable
data class HealthResponse(val status: String, val checks: Map<String, String> = emptyMap())
