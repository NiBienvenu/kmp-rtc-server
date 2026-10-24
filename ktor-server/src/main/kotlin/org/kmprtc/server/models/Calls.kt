package org.kmprtc.server.models

import kotlinx.serialization.Serializable

@Serializable
data class CallInviteRequest(
    val calleeUserId: String,
    val callerName: String,
    val target: String,
    val kind: String = "voice", // "voice" | "video"
)

