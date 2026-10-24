package org.kmprtc.server

import org.kmprtc.server.config.RtcConfig
import org.kmprtc.server.services.FileAsteriskProvisioner
import org.kmprtc.server.services.IceService
import org.kmprtc.server.services.SipCredential
import io.ktor.server.config.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

class ApplicationTest {

    private val rtcConfig = MapApplicationConfig(
        "voip.sipDomain" to "https://rtc.example.com/",
        "voip.transport" to "UDP",
        "voip.stun" to "stun:rtc.example.com:3478",
        "voip.turn.enabled" to "true",
        "voip.turn.host" to "rtc.example.com",
        "voip.turn.port" to "3478",
        "voip.turn.secret" to "s3cret",
    )

    @Test
    fun rtcConfigIsNormalized() {
        val rtc = RtcConfig.from(rtcConfig)
        assertEquals("rtc.example.com", rtc.sipDomain)
        assertEquals("udp", rtc.sipTransport)
        assertEquals("rtc.example.com:3478", rtc.stunServer)
        assertEquals("rtc.example.com:3478", rtc.turnServer)
    }

    @Test
    fun turnCredentialMatchesCoturnRestApi() {
        val cred = IceService(RtcConfig.from(rtcConfig)).turnCredential("user-1")!!
        assertTrue(cred.username.endsWith(":user-1"))
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec("s3cret".toByteArray(), "HmacSHA1")) }
        assertEquals(Base64.getEncoder().encodeToString(mac.doFinal(cred.username.toByteArray())), cred.password)
    }

    @Test
    fun provisionerWritesTemplatedUsers() = runBlocking {
        val file = Files.createTempDirectory("kmp").resolve("pjsip_users.conf")
        val p = FileAsteriskProvisioner(includeFile = file, reloadCommand = emptyList())
        p.syncAll(listOf(SipCredential("1000001", "pw")))
        p.ensureSipUser("1000001", "pw", "d")      // deja present: pas de doublon
        p.ensureSipUser("1000002", "pw2", "d")
        val text = Files.readString(file)
        assertEquals(1, Regex("""\[1000001]\(kmp-endpoint\)""").findAll(text).count())
        assertTrue(text.contains("[1000002-web](kmp-endpoint-webrtc)"))
        assertTrue(text.contains("[1000002](kmp-aor)"))
    }
}
