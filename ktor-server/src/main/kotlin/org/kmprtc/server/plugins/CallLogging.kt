package org.kmprtc.server.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.*
import org.slf4j.event.Level

fun Application.configureCallLogging() {
    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/") }
        format { call ->
            "${call.request.httpMethod.value} ${call.request.uri} → ${call.response.status()}"
        }
    }
}
