package org.kmprtc.server.repositories

import org.kmprtc.server.config.DatabaseFactory.dbQuery
import org.kmprtc.server.models.DeviceTokenTable
import org.kmprtc.server.models.DeviceTokenUpsertRequest
import org.kmprtc.server.models.SipAccountResponse
import org.kmprtc.server.models.SipPeerResponse
import org.kmprtc.server.models.SipAccountTable
import org.kmprtc.server.services.SipCredential
import org.kmprtc.server.utils.BASE_URL
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

class VoipRepositoryImpl(
    private val sipDomain: String = BASE_URL,
    private val sipTransport: String = "udp",
) : VoipRepository {
    private val log = LoggerFactory.getLogger(VoipRepositoryImpl::class.java)
    private val random = SecureRandom()

    override suspend fun upsertDeviceToken(userId: String, request: DeviceTokenUpsertRequest): Unit = dbQuery {
        val now = Instant.now().toString()

        // 1 user peut avoir plusieurs tokens (ex: ios: apns + voip, ou multi-device)
        val existing = DeviceTokenTable
            .select {
                (DeviceTokenTable.userId eq userId) and
                    (DeviceTokenTable.platform eq request.platform) and
                    (DeviceTokenTable.tokenType eq request.tokenType)
            }
            .singleOrNull()

        if (existing == null) {
            DeviceTokenTable.insert {
                it[id] = UUID.randomUUID().toString()
                it[DeviceTokenTable.userId] = userId
                it[platform] = request.platform
                it[tokenType] = request.tokenType
                it[token] = request.token
                it[updatedAt] = now
            }
        } else {
            DeviceTokenTable.update({
                (DeviceTokenTable.userId eq userId) and
                    (DeviceTokenTable.platform eq request.platform) and
                    (DeviceTokenTable.tokenType eq request.tokenType)
            }) {
                it[token] = request.token
                it[updatedAt] = now
            }
        }
    }

    override suspend fun getOrCreateSipAccount(userId: String): SipAccountResponse = dbQuery {
        val row = SipAccountTable.select { SipAccountTable.userId eq userId }.singleOrNull()
        if (row != null) {
            val username = row[SipAccountTable.sipUsername]
            val password = row[SipAccountTable.sipPassword]
            val domain = sipDomain
            log.info("SIP account exists userId={} username={}", userId, username)
            return@dbQuery SipAccountResponse(
                sipUri = "sip:$username@$domain",
                username = username,
                password = password,
                domain = domain,
                transport = sipTransport,
            )
        }

        // Username numerique sur 7 chiffres (1000000..7999999): il correspond au dialplan
        // (_[0-9a-zA-Z]. dans extensions.conf) et ne chevauche pas les salles (8XXXX) ni l'echo (600).
        var username: String
        do {
            username = (1_000_000 + random.nextInt(7_000_000)).toString()
        } while (SipAccountTable.select { SipAccountTable.sipUsername eq username }.any())
        val password = ByteArray(18).also { random.nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val now = Instant.now().toString()

        SipAccountTable.insert {
            it[SipAccountTable.userId] = userId
            it[sipUsername] = username
            it[sipPassword] = password
            it[realm] = sipDomain
            it[updatedAt] = now
        }

        log.info("SIP account created userId={} username={}", userId, username)
        SipAccountResponse(
            sipUri = "sip:$username@$sipDomain",
            username = username,
            password = password,
            domain = sipDomain,
            transport = sipTransport,
        )
    }

    override suspend fun getOrCreateSipPeer(userId: String): SipPeerResponse {
        // Crée si besoin, mais ne renvoie pas le password.
        val acc = getOrCreateSipAccount(userId)
        return SipPeerResponse(
            userId = userId,
            sipUri = acc.sipUri,
            username = acc.username,
            domain = acc.domain,
            transport = acc.transport,
        )
    }

    override suspend fun listSipCredentials(): List<SipCredential> = dbQuery {
        SipAccountTable.selectAll()
            .orderBy(SipAccountTable.sipUsername)
            .map { SipCredential(it[SipAccountTable.sipUsername], it[SipAccountTable.sipPassword]) }
    }

    override suspend fun findSipUsername(userId: String): String? = dbQuery {
        SipAccountTable.select { SipAccountTable.userId eq userId }
            .singleOrNull()
            ?.get(SipAccountTable.sipUsername)
    }

    override suspend fun getDeviceToken(userId: String, platform: String, tokenType: String): String? = dbQuery {
        DeviceTokenTable
            .select {
                (DeviceTokenTable.userId eq userId) and
                    (DeviceTokenTable.platform eq platform) and
                    (DeviceTokenTable.tokenType eq tokenType)
            }
            .orderBy(DeviceTokenTable.updatedAt to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(DeviceTokenTable.token)
    }
}
