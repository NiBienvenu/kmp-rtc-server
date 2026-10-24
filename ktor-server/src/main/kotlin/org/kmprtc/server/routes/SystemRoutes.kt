package org.kmprtc.server.routes

import org.kmprtc.server.config.DatabaseFactory.dbQuery
import org.kmprtc.server.models.ApiResponse
import org.kmprtc.server.models.HealthResponse
import org.kmprtc.server.models.SipAccountTable
import org.kmprtc.server.models.UserTable
import org.kmprtc.server.realtime.RealtimeHub
import org.kmprtc.server.repositories.CdrRepository
import org.kmprtc.server.repositories.WebhookRepository
import org.kmprtc.server.services.AsteriskCli
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.selectAll
import org.slf4j.LoggerFactory
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.time.LocalDateTime
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Monitoring (public, mais /metrics est limite aux reseaux prives par Nginx):
 *  - GET /health        : vivant (liveness)
 *  - GET /health/ready  : base + Asterisk OK (readiness)
 *  - GET /metrics       : format Prometheus
 */
fun Route.monitoringRoutes(cli: AsteriskCli, cdr: CdrRepository) {
    get("/health") {
        call.respond(HealthResponse("ok", mapOf("service" to "kmp-rtc-server")))
    }

    get("/health/ready") {
        val db = runCatching { dbQuery { UserTable.selectAll().limit(1).count() }; "ok" }.getOrElse { "error: ${it.message}" }
        val asterisk = if (cli.isUp()) "ok" else "down"
        val ok = db == "ok" && asterisk == "ok"
        call.respond(
            if (ok) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            HealthResponse(if (ok) "ok" else "degraded", mapOf("database" to db, "asterisk" to asterisk)),
        )
    }

    get("/metrics") {
        val users = runCatching { dbQuery { UserTable.selectAll().count() } }.getOrDefault(-1)
        val sip = runCatching { dbQuery { SipAccountTable.selectAll().count() } }.getOrDefault(-1)
        val calls24h = runCatching { cdr.countSince(LocalDateTime.now().minusHours(24)) }.getOrDefault(-1)
        val channels = cli.run("core show channels count").output
            .lineSequence().firstOrNull { it.contains("active channel") }
            ?.trim()?.substringBefore(' ')?.toIntOrNull() ?: -1
        val mem = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        val body = buildString {
            appendLine("# TYPE kmp_users_total gauge"); appendLine("kmp_users_total $users")
            appendLine("# TYPE kmp_sip_accounts_total gauge"); appendLine("kmp_sip_accounts_total $sip")
            appendLine("# TYPE kmp_calls_last_24h gauge"); appendLine("kmp_calls_last_24h $calls24h")
            appendLine("# TYPE kmp_asterisk_active_channels gauge"); appendLine("kmp_asterisk_active_channels $channels")
            appendLine("# TYPE kmp_ws_connected_users gauge"); appendLine("kmp_ws_connected_users ${RealtimeHub.connectedUsers()}")
            appendLine("# TYPE kmp_jvm_heap_used_bytes gauge"); appendLine("kmp_jvm_heap_used_bytes ${mem.used}")
            appendLine("# TYPE kmp_jvm_heap_max_bytes gauge"); appendLine("kmp_jvm_heap_max_bytes ${mem.max}")
        }
        call.respondText(body, ContentType.parse("text/plain; version=0.0.4"))
    }
}

/**
 * Webhooks entrants: POST /webhooks/{source}
 * En-tete obligatoire: X-Signature: sha256=<hex(HMAC-SHA256(WEBHOOK_SECRET, body))>
 */
fun Route.webhookRoutes(repo: WebhookRepository, secret: String?) {
    val log = LoggerFactory.getLogger("Webhooks")
    post("/webhooks/{source}") {
        if (secret.isNullOrBlank()) {
            return@post call.respond(HttpStatusCode.ServiceUnavailable, ApiResponse<Unit>(false, "Webhooks desactives (WEBHOOK_SECRET vide)"))
        }
        val source = call.parameters["source"]!!
        require(source.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "source invalide" }
        val body = call.receiveText()
        require(body.length <= 1_000_000) { "payload trop gros" }

        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        val expected = "sha256=" + mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
        val given = call.request.headers["X-Signature"].orEmpty()
        if (!MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())) {
            return@post call.respond(HttpStatusCode.Unauthorized, ApiResponse<Unit>(false, "Signature invalide"))
        }

        val type = runCatching {
            Json.parseToJsonElement(body).jsonObject["type"]?.jsonPrimitive?.content
        }.getOrNull().orEmpty()
        val id = repo.save(source, type, body)
        log.info("Webhook received source={} type={} id={}", source, type, id)
        call.respond(HttpStatusCode.Accepted, ApiResponse(true, "Recu", mapOf("id" to id)))
    }
}
