package org.kmprtc.server.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.forwardedheaders.*

fun Application.configureHTTP() {
    // Ktor est derriere Nginx: on recupere la vraie IP client et le schema (https).
    install(XForwardedHeaders)

    // CORS_ALLOWED_HOSTS="rtc.example.com,app.example.com" ou "*" (dev)
    val allowedHosts = (environment.config.propertyOrNull("http.corsAllowedHosts")?.getString() ?: "*")
        .split(',').map { it.trim() }.filter { it.isNotEmpty() }

    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        if (allowedHosts.isEmpty() || "*" in allowedHosts) {
            anyHost()
        } else {
            allowedHosts.forEach { allowHost(it, schemes = listOf("https", "http")) }
        }
    }
}
