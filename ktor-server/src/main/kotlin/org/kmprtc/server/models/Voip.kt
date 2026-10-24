package org.kmprtc.server.models

import org.kmprtc.server.utils.BASE_URL
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Table

/**
 * Token push par device.
 * - Android: FCM
 * - iOS: APNs + VoIP (PushKit)
 */
object DeviceTokenTable : Table("device_tokens") {
    val id = varchar("id", 36)
    val userId = varchar("user_id", 36).index()
    val platform = varchar("platform", 16)      // android / ios
    val tokenType = varchar("token_type", 16)   // fcm / apns / voip
    val token = varchar("token", 512)
    val updatedAt = varchar("updated_at", 50).default("")
    override val primaryKey = PrimaryKey(id)
}

/**
 * Compte SIP provisionné pour l'utilisateur.
 *
 * NOTE: pour Asterisk/PJSIP, le password doit souvent être disponible en clair
 * (auth_type=userpass) ou sous forme md5_cred. En production, stocker chiffré
 * au repos ou générer md5_cred selon votre stratégie.
 */
object SipAccountTable : Table("sip_accounts") {
    val userId = varchar("user_id", 36)
    val sipUsername = varchar("sip_username", 64).uniqueIndex()
    val sipPassword = varchar("sip_password", 128)
    val realm = varchar("realm", 128).default(BASE_URL)
    val updatedAt = varchar("updated_at", 50).default("")
    override val primaryKey = PrimaryKey(userId)
}

@Serializable
data class DeviceTokenUpsertRequest(
    val platform: String,   // "android"|"ios"
    val tokenType: String,  // "fcm"|"apns"|"voip"
    val token: String,
)

@Serializable
data class SipAccountResponse(
    val sipUri: String,     // ex: sip:100001@35.226.56.167
    val username: String,   // ex: 100001
    val password: String,
    val domain: String,     // 35.226.56.167
    val transport: String,  // udp | tcp | tls
    val stun: String? = null,           // "host:port"
    val turn: String? = null,           // "host:port" (coturn integre)
    val turnUsername: String? = null,   // identifiant TURN temporaire
    val turnPassword: String? = null,
    val iceEnabled: Boolean = true,
    val wsUri: String? = null,          // WebSocket SIP pour clients Web (WebRTC)
    val webUsername: String? = null,    // endpoint WebRTC associe ("<username>-web")
)

/**
 * Identité SIP d'un utilisateur (sans secret). Utilisé par les autres clients pour composer un appel.
 */
@Serializable
data class SipPeerResponse(
    val userId: String,
    val sipUri: String,
    val username: String,
    val domain: String,
    val transport: String,
)
