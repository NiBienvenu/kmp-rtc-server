package org.kmprtc.server

import org.kmprtc.server.plugins.configureCallLogging
import org.kmprtc.server.plugins.configureHTTP
import org.kmprtc.server.plugins.configureRouting
import org.kmprtc.server.plugins.configureSecurity
import org.kmprtc.server.plugins.configureSerialization
import org.kmprtc.server.plugins.configureStatusPages
import org.kmprtc.server.plugins.configureWebSockets
import org.kmprtc.server.config.DatabaseFactory
import io.ktor.server.application.*
import io.ktor.server.netty.*

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

fun Application.module() {
    // 1. Base de données — toujours en premier
    DatabaseFactory.init(environment.config)

    // 2. Plugins transversaux
    configureSerialization()
    configureHTTP()
    configureSecurity()
    configureStatusPages()
    configureCallLogging()
    configureWebSockets()

    // Lecture des fichiers par morceaux (Range): lecture audio/video dans un lecteur
    install(io.ktor.server.plugins.partialcontent.PartialContent)
    install(io.ktor.server.plugins.autohead.AutoHeadResponse)

    // 3. Routes applicatives
    configureRouting()
}
