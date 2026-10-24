package org.kmprtc.server.services

import org.kmprtc.server.config.RtcConfig
import org.kmprtc.server.models.IceServer
import org.kmprtc.server.models.IceServersResponse
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Genere des identifiants TURN temporaires (methode "TURN REST API", compatible coturn use-auth-secret):
 *   username = "<expiration_unix>:<userId>"
 *   password = base64(HMAC-SHA1(TURN_SECRET, username))
 * Aucun mot de passe TURN fixe n'est donc distribue aux applications.
 */
class IceService(private val rtc: RtcConfig) {

    data class TurnCredential(val username: String, val password: String)

    fun turnCredential(userId: String): TurnCredential? {
        val secret = rtc.turnSecret ?: return null
        if (rtc.turnServer == null) return null
        val expiry = System.currentTimeMillis() / 1000 + rtc.turnTtlSeconds
        val username = "$expiry:$userId"
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA1"))
        val password = Base64.getEncoder().encodeToString(mac.doFinal(username.toByteArray()))
        return TurnCredential(username, password)
    }

    /** Format WebRTC (RTCPeerConnection iceServers), utile pour les clients Web et Desktop. */
    fun iceServers(userId: String): IceServersResponse {
        val servers = mutableListOf<IceServer>()
        rtc.stunServer?.let { servers += IceServer(urls = listOf("stun:$it")) }
        val turn = rtc.turnServer
        val cred = turnCredential(userId)
        if (turn != null && cred != null) {
            servers += IceServer(
                urls = listOf("turn:$turn?transport=udp", "turn:$turn?transport=tcp"),
                username = cred.username,
                credential = cred.password,
            )
        }
        return IceServersResponse(servers, rtc.turnTtlSeconds)
    }
}
