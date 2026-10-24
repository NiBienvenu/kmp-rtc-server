// API Ktor de kmp-rtc-server.
// Toutes les dependances sont open source (Apache 2.0, MIT, BSD, LGPL, EPL): voir docs/11-licences.md
plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("io.ktor.plugin") version "3.3.3"
    application
}

group = "org.kmprtc"
version = "1.0.0"

val ktorVersion = "3.4.3"
val exposedVersion = "0.50.1"

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("org.kmprtc.server.ApplicationKt")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=false")
}

dependencies {
    // Ktor serveur (Apache 2.0)
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt-jvm:$ktorVersion")      // + java-jwt (MIT)
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-cors-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-forwarded-header-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-partial-content-jvm:$ktorVersion") // lecture des enregistrements (Range)
    implementation("io.ktor:ktor-server-auto-head-response-jvm:$ktorVersion")

    // Base de donnees: Exposed (Apache 2.0), MariaDB Connector/J (LGPL 2.1), HikariCP (Apache 2.0)
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.1")
    implementation("com.zaxxer:HikariCP:5.1.0")

    // Mots de passe: jBCrypt (ISC)
    implementation("org.mindrot:jbcrypt:0.4")

    // Logs: Logback (EPL 1.0 / LGPL 2.1)
    implementation("ch.qos.logback:logback-classic:1.5.32")

    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation(kotlin("test-junit"))
}
