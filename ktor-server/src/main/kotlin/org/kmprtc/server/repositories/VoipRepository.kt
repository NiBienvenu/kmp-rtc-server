package org.kmprtc.server.repositories

import org.kmprtc.server.models.DeviceTokenUpsertRequest
import org.kmprtc.server.models.SipAccountResponse
import org.kmprtc.server.models.SipPeerResponse
import org.kmprtc.server.services.SipCredential

interface VoipRepository {
    suspend fun upsertDeviceToken(userId: String, request: DeviceTokenUpsertRequest)
    suspend fun getOrCreateSipAccount(userId: String): SipAccountResponse
    suspend fun getOrCreateSipPeer(userId: String): SipPeerResponse
    suspend fun listSipCredentials(): List<SipCredential>
    suspend fun findSipUsername(userId: String): String?
    suspend fun getDeviceToken(userId: String, platform: String, tokenType: String): String?
}
