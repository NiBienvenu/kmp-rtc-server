package org.kmprtc.server.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

data class SipCredential(val username: String, val password: String)

interface AsteriskProvisioner {
    suspend fun ensureSipUser(username: String, password: String, domain: String)

    /** Reecrit tous les comptes SIP (la base est la source de verite). */
    suspend fun syncAll(accounts: List<SipCredential>) = Unit
}

class NoopAsteriskProvisioner : AsteriskProvisioner {
    override suspend fun ensureSipUser(username: String, password: String, domain: String) = Unit
}

/**
 * Mode par defaut de kmp-rtc-server: Asterisk tourne dans le meme conteneur.
 *
 * Ktor genere un fichier inclus par pjsip.conf (#include). Les reglages (codecs, NAT, SRTP, ICE)
 * sont dans les templates de pjsip.conf; ce fichier ne contient que les identites.
 * Ainsi Ktor et la config Asterisk evoluent separement.
 */
class FileAsteriskProvisioner(
    private val includeFile: Path,
    private val endpointTemplate: String = "kmp-endpoint",
    private val webrtcTemplate: String = "kmp-endpoint-webrtc",
    private val authTemplate: String = "kmp-auth",
    private val aorTemplate: String = "kmp-aor",
    private val webrtcEndpoints: Boolean = true,
    private val reloadCommand: List<String> = listOf("asterisk", "-rx", "module reload res_pjsip.so"),
) : AsteriskProvisioner {

    private val log = LoggerFactory.getLogger(FileAsteriskProvisioner::class.java)
    private val mutex = Mutex()

    override suspend fun ensureSipUser(username: String, password: String, domain: String) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val existing = readExisting()
                if (containsCategory(existing, username)) {
                    log.debug("Asterisk provisioning: SIP user {} already present in {}", username, includeFile)
                    return@withContext
                }
                writeAtomically(existing + buildBlock(username, password))
                log.info("Asterisk provisioning: wrote SIP user {} into {}", username, includeFile)
                reload()
            }
        }
    }

    override suspend fun syncAll(accounts: List<SipCredential>) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val content = buildString {
                    append("; Fichier genere par Ktor (kmp-rtc-server). Ne pas modifier a la main.\n")
                    accounts.forEach { append(buildBlock(it.username, it.password)) }
                }
                if (content == readExisting()) {
                    log.info("Asterisk provisioning: {} SIP users already in sync", accounts.size)
                    return@withContext
                }
                writeAtomically(content)
                log.info("Asterisk provisioning: synced {} SIP users into {}", accounts.size, includeFile)
                reload()
            }
        }
    }

    private fun buildBlock(username: String, password: String): String = buildString {
        require(username.matches(Regex("[A-Za-z0-9_.-]+"))) { "SIP username invalide" }
        require(!password.contains('\n')) { "SIP password invalide" }
        append("\n; --- user: ").append(username).append(" ---\n")
        append("[").append(username).append("](").append(endpointTemplate).append(")\n")
        append("auth=").append(username).append("\n")
        append("aors=").append(username).append("\n")
        append("[").append(username).append("](").append(authTemplate).append(")\n")
        append("username=").append(username).append("\n")
        append("password=").append(password).append("\n")
        append("[").append(username).append("](").append(aorTemplate).append(")\n")
        if (webrtcEndpoints) {
            val web = "$username-web"
            append("[").append(web).append("](").append(webrtcTemplate).append(")\n")
            append("auth=").append(web).append("\n")
            append("aors=").append(web).append("\n")
            append("[").append(web).append("](").append(authTemplate).append(")\n")
            append("username=").append(web).append("\n")
            append("password=").append(password).append("\n")
            append("[").append(web).append("](").append(aorTemplate).append(")\n")
        }
    }

    private fun readExisting(): String =
        runCatching { Files.readString(includeFile, StandardCharsets.UTF_8) }.getOrNull().orEmpty()

    private fun writeAtomically(content: String) {
        includeFile.parent?.let { Files.createDirectories(it) }
        val tmp = includeFile.resolveSibling(includeFile.fileName.toString() + ".tmp")
        Files.writeString(tmp, content, StandardCharsets.UTF_8)
        Files.move(tmp, includeFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun reload() {
        if (reloadCommand.isEmpty()) return
        runCatching {
            val p = ProcessBuilder(reloadCommand).redirectErrorStream(true).start()
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                log.warn("Asterisk provisioning: reload timed out: {}", reloadCommand.joinToString(" "))
                return
            }
            val out = p.inputStream.bufferedReader().readText().trim()
            if (p.exitValue() != 0) {
                // Normal au demarrage si Asterisk n'est pas encore pret: il lira le fichier en demarrant.
                log.warn("Asterisk provisioning: reload failed (exit {}): {}", p.exitValue(), out.take(300))
            } else {
                log.info("Asterisk provisioning: reload OK")
            }
        }.onFailure { log.warn("Asterisk provisioning: reload error: {}", it.message) }
    }

    private fun containsCategory(existing: String, username: String): Boolean {
        // Section [username] ou [username](template)
        val needle = "[$username]"
        return existing
            .lineSequence()
            .any { line ->
                val t = line.trim()
                t.startsWith(needle) && (t.length == needle.length || t[needle.length] == '(')
            }
    }
}
