package org.kmprtc.server.config

import io.ktor.server.config.*

/**
 * Parametres temps reel (SIP / STUN / TURN / WebRTC) lus depuis application.conf (bloc "voip").
 * Dans kmp-rtc-server, ils viennent des variables d'environnement du conteneur.
 */
data class RtcConfig(
    val sipDomain: String,
    val sipTransport: String,
    val stunServer: String?,      // "host:port"
    val turnEnabled: Boolean,
    val turnHost: String?,
    val turnPort: Int,
    val turnSecret: String?,
    val turnTtlSeconds: Long,
    val wsUri: String?,
    val conferencePrefix: String,
) {
    val turnServer: String? get() = if (turnEnabled && !turnHost.isNullOrBlank()) "$turnHost:$turnPort" else null

    companion object {
        fun from(config: ApplicationConfig): RtcConfig {
            val voip = config.config("voip")
            val turn = voip.config("turn")
            fun ApplicationConfig.str(key: String): String? =
                propertyOrNull(key)?.getString()?.trim()?.takeIf { it.isNotEmpty() }

            val sipDomain = (voip.str("sipDomain") ?: "127.0.0.1")
                .removePrefix("http://")
                .removePrefix("https://")
                .trimEnd('/')

            return RtcConfig(
                sipDomain = sipDomain,
                sipTransport = (voip.str("transport") ?: "udp").lowercase(),
                stunServer = voip.str("stun")?.removePrefix("stun:"),
                turnEnabled = turn.str("enabled")?.toBoolean() ?: false,
                turnHost = turn.str("host") ?: sipDomain,
                turnPort = turn.str("port")?.toIntOrNull() ?: 3478,
                turnSecret = turn.str("secret"),
                turnTtlSeconds = turn.str("ttlSeconds")?.toLongOrNull() ?: 86_400L,
                wsUri = voip.str("wsUri"),
                conferencePrefix = voip.str("conferencePrefix") ?: "8",
            )
        }
    }
}
