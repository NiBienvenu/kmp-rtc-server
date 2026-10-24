package org.kmprtc.server.repositories

import org.kmprtc.server.config.DatabaseFactory.dbQuery
import org.kmprtc.server.models.CallRecord
import org.kmprtc.server.models.CallStats
import org.kmprtc.server.models.CdrTable
import org.kmprtc.server.models.ConferenceRoom
import org.kmprtc.server.models.ConferenceRoomTable
import org.kmprtc.server.models.CreateRoomRequest
import org.kmprtc.server.models.DailyCallStats
import org.kmprtc.server.models.WebhookEventTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

// ─── Salles de conference ─────────────────────────────────────────────────────

class RoomRepository(
    private val sipDomain: String,
    private val prefix: String = "8",
) {
    private val random = SecureRandom()

    private fun rowToRoom(row: ResultRow, withPin: Boolean) = ConferenceRoom(
        id              = row[ConferenceRoomTable.id],
        roomNumber      = row[ConferenceRoomTable.roomNumber],
        name            = row[ConferenceRoomTable.name],
        hasPin          = !row[ConferenceRoomTable.pin].isNullOrBlank(),
        pin             = if (withPin) row[ConferenceRoomTable.pin] else null,
        maxParticipants = row[ConferenceRoomTable.maxParticipants],
        video           = row[ConferenceRoomTable.video],
        ownerId         = row[ConferenceRoomTable.ownerId],
        active          = row[ConferenceRoomTable.active],
        createdAt       = row[ConferenceRoomTable.createdAt],
        sipUri          = "sip:${row[ConferenceRoomTable.roomNumber]}@$sipDomain",
    )

    suspend fun create(ownerId: String, request: CreateRoomRequest): ConferenceRoom = dbQuery {
        // Numero de salle: prefixe + 4 chiffres (ex: 81234), correspond a _8XXXX dans extensions.conf
        var number: String
        do {
            number = prefix + (1000 + random.nextInt(9000)).toString()
        } while (ConferenceRoomTable.select { ConferenceRoomTable.roomNumber eq number }.any())

        val id = UUID.randomUUID().toString()
        ConferenceRoomTable.insert {
            it[ConferenceRoomTable.id] = id
            it[roomNumber]      = number
            it[name]            = request.name
            it[pin]             = request.pin?.takeIf { p -> p.isNotBlank() }
            it[maxParticipants] = request.maxParticipants
            it[video]           = request.video
            it[ConferenceRoomTable.ownerId] = ownerId
            it[active]          = true
            it[createdAt]       = Instant.now().toString()
        }
        rowToRoom(ConferenceRoomTable.select { ConferenceRoomTable.id eq id }.single(), withPin = true)
    }

    suspend fun list(callerId: String, isAdmin: Boolean): List<ConferenceRoom> = dbQuery {
        ConferenceRoomTable.select { ConferenceRoomTable.active eq true }
            .orderBy(ConferenceRoomTable.createdAt to SortOrder.DESC)
            .map { rowToRoom(it, withPin = isAdmin || it[ConferenceRoomTable.ownerId] == callerId) }
    }

    suspend fun findById(id: String, callerId: String, isAdmin: Boolean): ConferenceRoom? = dbQuery {
        ConferenceRoomTable.select { ConferenceRoomTable.id eq id }
            .singleOrNull()
            ?.let { rowToRoom(it, withPin = isAdmin || it[ConferenceRoomTable.ownerId] == callerId) }
    }

    suspend fun ownerOf(id: String): String? = dbQuery {
        ConferenceRoomTable.select { ConferenceRoomTable.id eq id }
            .singleOrNull()
            ?.get(ConferenceRoomTable.ownerId)
    }

    /** Desactive la salle (Asterisk refuse alors l'acces, voir func_odbc.conf). */
    suspend fun deactivate(id: String): Boolean = dbQuery {
        ConferenceRoomTable.update({ ConferenceRoomTable.id eq id }) { it[active] = false } > 0
    }
}

// ─── CDR / statistiques ───────────────────────────────────────────────────────

class CdrRepository(private val conferencePrefix: String = "8") {

    private fun rowToRecord(row: ResultRow, sipUsername: String?) = CallRecord(
        id          = row[CdrTable.id],
        calldate    = row[CdrTable.calldate].toString(),
        src         = row[CdrTable.src],
        dst         = row[CdrTable.dst],
        direction   = when {
            row[CdrTable.dst].matches(Regex("$conferencePrefix\\d{4}")) -> "conference"
            sipUsername != null && row[CdrTable.src] == sipUsername -> "outgoing"
            sipUsername != null && row[CdrTable.dst].removeSuffix("-web") == sipUsername -> "incoming"
            else -> "other"
        },
        duration    = row[CdrTable.duration],
        billsec     = row[CdrTable.billsec],
        disposition = row[CdrTable.disposition],
        uniqueid    = row[CdrTable.uniqueid],
    )

    suspend fun historyFor(sipUsername: String, limit: Int): List<CallRecord> = dbQuery {
        CdrTable.select {
            (CdrTable.src eq sipUsername) or (CdrTable.dst eq sipUsername) or (CdrTable.dst eq "$sipUsername-web")
        }
            .orderBy(CdrTable.calldate to SortOrder.DESC)
            .limit(limit)
            .map { rowToRecord(it, sipUsername) }
    }

    suspend fun recent(limit: Int): List<CallRecord> = dbQuery {
        CdrTable.selectAll()
            .orderBy(CdrTable.calldate to SortOrder.DESC)
            .limit(limit)
            .map { rowToRecord(it, null) }
    }

    suspend fun stats(days: Int): CallStats = dbQuery {
        // Valeur generee cote serveur (pas d'entree utilisateur): pas de risque d'injection SQL.
        val since = LocalDateTime.now().minusDays(days.toLong()).withNano(0).toString().replace('T', ' ')
        val perDay = mutableListOf<DailyCallStats>()
        // Requete SQL simple (agregats par jour), plus lisible que l'equivalent Exposed.
        TransactionManager.current().exec(
            """
            SELECT DATE(calldate) AS d, COUNT(*) AS calls,
                   SUM(disposition = 'ANSWERED') AS answered, COALESCE(SUM(billsec), 0) AS billsec
            FROM cdr WHERE calldate >= '$since' GROUP BY DATE(calldate) ORDER BY d
            """.trimIndent(),
        ) { rs ->
            while (rs.next()) {
                perDay += DailyCallStats(rs.getString("d"), rs.getLong("calls"), rs.getLong("answered"), rs.getLong("billsec"))
            }
        }
        val total = perDay.sumOf { it.calls }
        val answered = perDay.sumOf { it.answered }
        val billsec = perDay.sumOf { it.billsec }
        CallStats(
            totalCalls = total,
            answeredCalls = answered,
            missedCalls = total - answered,
            totalBillsec = billsec,
            averageBillsec = if (answered > 0) billsec.toDouble() / answered else 0.0,
            perDay = perDay,
        )
    }

    suspend fun countSince(since: LocalDateTime): Long = dbQuery {
        CdrTable.select { CdrTable.calldate greaterEq since }.count()
    }
}

// ─── Webhooks ─────────────────────────────────────────────────────────────────

class WebhookRepository {
    suspend fun save(source: String, eventType: String, payload: String): String = dbQuery {
        val id = UUID.randomUUID().toString()
        WebhookEventTable.insert {
            it[WebhookEventTable.id] = id
            it[WebhookEventTable.sourceName] = source
            it[WebhookEventTable.eventType] = eventType
            it[WebhookEventTable.payload] = payload
            it[receivedAt] = Instant.now().toString()
        }
        id
    }
}
