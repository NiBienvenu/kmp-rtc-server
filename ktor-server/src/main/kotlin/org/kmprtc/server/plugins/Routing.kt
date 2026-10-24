package org.kmprtc.server.plugins

import org.kmprtc.server.config.RtcConfig
import org.kmprtc.server.models.RegisterRequest
import org.kmprtc.server.repositories.CdrRepository
import org.kmprtc.server.repositories.RoomRepository
import org.kmprtc.server.repositories.UserRepositoryImpl
import org.kmprtc.server.repositories.VoipRepositoryImpl
import org.kmprtc.server.repositories.WebhookRepository
import org.kmprtc.server.routes.asteriskAdminRoutes
import org.kmprtc.server.routes.authRoutes
import org.kmprtc.server.routes.callRoutes
import org.kmprtc.server.routes.cdrRoutes
import org.kmprtc.server.routes.iceRoutes
import org.kmprtc.server.routes.monitoringRoutes
import org.kmprtc.server.routes.recordingRoutes
import org.kmprtc.server.routes.roomRoutes
import org.kmprtc.server.routes.userRoutes
import org.kmprtc.server.routes.voipRoutes
import org.kmprtc.server.routes.webhookRoutes
import org.kmprtc.server.services.AsteriskCli
import org.kmprtc.server.services.AsteriskProvisioner
import org.kmprtc.server.services.AuthService
import org.kmprtc.server.services.CallInviteService
import org.kmprtc.server.services.IceService
import org.kmprtc.server.services.RecordingService
import org.kmprtc.server.services.UserService
import org.kmprtc.server.services.VoipService
import org.kmprtc.server.services.FileAsteriskProvisioner
import org.kmprtc.server.services.NoopAsteriskProvisioner
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.config.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.util.logging.*
import org.kmprtc.server.realtime.RealtimeHub
import kotlinx.coroutines.launch
import java.nio.file.Paths

fun Application.configureRouting() {
    val log = environment.log
    val config = environment.config

    // Injection de dépendances manuelle (remplaçable par Koin/Kodein)
    val userRepository = UserRepositoryImpl()
    val authService    = AuthService(userRepository)
    val userService    = UserService(userRepository)

    val rtc = RtcConfig.from(config)
    log.info("RTC config: sipDomain={} transport={} stun={} turn={}", rtc.sipDomain, rtc.sipTransport, rtc.stunServer, rtc.turnServer)

    val voipRepository = VoipRepositoryImpl(
        sipDomain = rtc.sipDomain,
        sipTransport = rtc.sipTransport,
    )

    val asteriskCli = AsteriskCli(config.propertyOrNull("asterisk.cli")?.getString() ?: "asterisk")
    val asteriskProvisioner = createAsteriskProvisioner(config, log)
    val iceService     = IceService(rtc)
    val voipService    = VoipService(voipRepository, asteriskProvisioner, rtc, iceService)
    val pushService    = CallInviteService()
    val roomRepository = RoomRepository(rtc.sipDomain, rtc.conferencePrefix)
    val cdrRepository  = CdrRepository(rtc.conferencePrefix)
    val webhookSecret  = config.propertyOrNull("webhooks.secret")?.getString()
    val recordingService = RecordingService(
        baseDir = config.propertyOrNull("recordings.dir")?.getString() ?: "/var/spool/kmp-rtc/recordings",
        maxUploadBytes = (config.propertyOrNull("recordings.maxUploadMb")?.getString()?.toLongOrNull() ?: 500L) * 1_048_576,
    )

    // Au demarrage: compte admin optionnel + resynchronisation des comptes SIP vers Asterisk.
    launch {
        bootstrapAdmin(config, authService, userRepository, log)
        runCatching { voipService.syncAsterisk() }
            .onFailure { log.warn("Initial Asterisk sync failed: {}", it.message) }
    }

    routing {

        // Monitoring: /health, /health/ready, /metrics
        monitoringRoutes(asteriskCli, cdrRepository)

        // Groupe /auth
        route("/auth") {
            authRoutes(authService, voipService)
        }

        // Routes CRUD /users (protégées JWT)
        userRoutes(userService)

        // Provisioning SIP + tokens push (protégées JWT)
        voipRoutes(voipService)

        // Webhooks entrants (signature HMAC, pas de JWT)
        webhookRoutes(WebhookRepository(), webhookSecret)

        authenticate("jwt-auth") {
            // Push "call invite"
            callRoutes(pushService)
            // Salles de conference, journal d'appels, ICE, admin Asterisk
            roomRoutes(roomRepository)
            cdrRoutes(cdrRepository, voipRepository)
            iceRoutes(iceService)
            asteriskAdminRoutes(asteriskCli)
            // Enregistrements audio/video des appels et conferences
            recordingRoutes(recordingService, voipRepository)
        }

        // WebSocket temps réel (présence / signaux d'appels / QoE)
        authenticate("jwt-auth") {
            webSocket("/ws") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("userId").asString()

                RealtimeHub.add(userId, this)
                try {
                    // Pour l'instant on "drain" les frames (évite les timeouts).
                    // Les messages structurés (JSON) seront ajoutés avec un protocole d'événements.
                    for (frame in incoming) {
                        if (frame is io.ktor.websocket.Frame.Close) break
                    }
                } finally {
                    RealtimeHub.remove(userId, this)
                }
            }
        }
    }
}

private fun createAsteriskProvisioner(config: ApplicationConfig, log: Logger): AsteriskProvisioner = runCatching {
    val asteriskConfig = config.config("asterisk").config("provisioning")
    fun opt(key: String) = asteriskConfig.propertyOrNull(key)?.getString()?.takeIf { it.isNotBlank() }

    val provisioningEnabled = opt("enabled")?.toBoolean() ?: false
    if (!provisioningEnabled) {
        log.info("Asterisk provisioning disabled (asterisk.provisioning.enabled=false). SIP users will NOT be written to pjsip include file.")
        return@runCatching NoopAsteriskProvisioner()
    }
    run {
        val includeFile = opt("includeFile") ?: "/var/lib/kmp-rtc/asterisk/pjsip_users.conf"
        val reloadCommand = asteriskConfig.propertyOrNull("reloadCommand")?.getList()
            ?.filter { it.isNotBlank() }
            ?: listOf("asterisk", "-rx", "module reload res_pjsip.so")
        log.info("Asterisk provisioning enabled (mode=file). includeFile={}", includeFile)
        FileAsteriskProvisioner(
            includeFile = Paths.get(includeFile),
            endpointTemplate = opt("endpointTemplate") ?: "kmp-endpoint",
            webrtcTemplate = opt("webrtcTemplate") ?: "kmp-endpoint-webrtc",
            authTemplate = opt("authTemplate") ?: "kmp-auth",
            aorTemplate = opt("aorTemplate") ?: "kmp-aor",
            webrtcEndpoints = opt("webrtcEndpoints")?.toBoolean() ?: true,
            reloadCommand = reloadCommand,
        )
    }
}.getOrElse {
    log.warn("Asterisk provisioning init failed; falling back to NoopAsteriskProvisioner. Reason: {}", it.message)
    NoopAsteriskProvisioner()
}

private suspend fun bootstrapAdmin(
    config: ApplicationConfig,
    authService: AuthService,
    userRepository: UserRepositoryImpl,
    log: Logger,
) {
    val email = config.propertyOrNull("admin.email")?.getString()?.trim().orEmpty()
    val password = config.propertyOrNull("admin.password")?.getString().orEmpty()
    if (email.isBlank() || password.isBlank()) return
    if (userRepository.findByEmail(email) != null) return
    runCatching {
        authService.register(
            RegisterRequest(displayName = "Administrator", email = email, phone = "", password = password, role = "admin"),
            trustedRole = true,
        )
    }.onSuccess { log.info("Admin account created: {}", email) }
        .onFailure { log.warn("Admin bootstrap failed: {}", it.message) }
}
