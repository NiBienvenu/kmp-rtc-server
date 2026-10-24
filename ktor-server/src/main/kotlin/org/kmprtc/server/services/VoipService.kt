package org.kmprtc.server.services

import org.kmprtc.server.config.RtcConfig
import org.kmprtc.server.models.DeviceTokenUpsertRequest
import org.kmprtc.server.models.SipAccountResponse
import org.kmprtc.server.models.SipPeerResponse
import org.kmprtc.server.repositories.VoipRepository
import org.slf4j.LoggerFactory

class VoipService(
    private val voipRepository: VoipRepository,
    private val asteriskProvisioner: AsteriskProvisioner = NoopAsteriskProvisioner(),
    private val rtc: RtcConfig? = null,
    private val iceService: IceService? = null,
) {
    private val log = LoggerFactory.getLogger(VoipService::class.java)

    suspend fun upsertDeviceToken(userId: String, request: DeviceTokenUpsertRequest) {
        require(request.platform in setOf("android", "ios", "desktop", "web")) { "platform invalide" }
        require(request.tokenType in setOf("fcm", "apns", "voip")) { "tokenType invalide" }
        require(request.token.isNotBlank()) { "token manquant" }
        voipRepository.upsertDeviceToken(userId, request)
    }

    suspend fun getSipAccount(userId: String): SipAccountResponse {
        val acc = voipRepository.getOrCreateSipAccount(userId)
        // Ensure the SIP identity exists on Asterisk (file include / realtime, depending on provisioner).
        log.info("SIP account for userId={} username={} domain={} transport={}", userId, acc.username, acc.domain, acc.transport)
        asteriskProvisioner.ensureSipUser(acc.username, acc.password, acc.domain)

        // Parametres NAT (STUN / TURN / ICE) et WebRTC fournis par l'image kmp-rtc-server.
        val turn = iceService?.turnCredential(userId)
        return acc.copy(
            stun = rtc?.stunServer,
            turn = if (turn != null) rtc?.turnServer else null,
            turnUsername = turn?.username,
            turnPassword = turn?.password,
            iceEnabled = true,
            wsUri = rtc?.wsUri,
            webUsername = "${acc.username}-web",
        )
    }

    suspend fun getSipPeer(userId: String): SipPeerResponse =
        voipRepository.getOrCreateSipPeer(userId)

    /** Au demarrage: reecrit le fichier PJSIP depuis la base (utile apres perte du volume ou migration). */
    suspend fun syncAsterisk() {
        val all = voipRepository.listSipCredentials()
        asteriskProvisioner.syncAll(all)
    }
}
