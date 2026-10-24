package org.kmprtc.server.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Acces en lecture a la CLI Asterisk (socket de controle partage dans le conteneur).
 * L'utilisateur "ktor" fait partie du groupe "asterisk" (voir Dockerfile).
 */
class AsteriskCli(private val binary: String = "asterisk") {

    data class Result(val exitCode: Int, val output: String)

    suspend fun run(command: String, timeoutSeconds: Long = 5): Result = withContext(Dispatchers.IO) {
        runCatching {
            val p = ProcessBuilder(binary, "-rx", command).redirectErrorStream(true).start()
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return@runCatching Result(124, "timeout")
            }
            Result(p.exitValue(), p.inputStream.bufferedReader().readText())
        }.getOrElse { Result(127, it.message ?: "asterisk CLI unavailable") }
    }

    suspend fun isUp(): Boolean = run("core show uptime seconds", 3).let {
        it.exitCode == 0 && it.output.contains("System uptime")
    }
}
