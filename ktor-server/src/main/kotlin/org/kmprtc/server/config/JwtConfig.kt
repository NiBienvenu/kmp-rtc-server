package org.kmprtc.server.config

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.config.*
import java.util.Date

object JwtConfig {

    private lateinit var secret: String
    lateinit var domain: String
    lateinit var audience: String
    lateinit var realm: String
    private var expiration: Long = 86_400_000L // 24h par défaut

    fun init(config: ApplicationConfig) {
        secret     = config.property("jwt.secret").getString()
        domain     = config.property("jwt.domain").getString()
        audience   = config.property("jwt.audience").getString()
        realm      = config.property("jwt.realm").getString()
        expiration = config.property("jwt.expiration").getString().toLong()
    }

    val algorithm: Algorithm get() = Algorithm.HMAC256(secret)

    val verifier = lazy {
        JWT.require(algorithm)
            .withAudience(audience)
            .withIssuer(domain)
            .build()
    }

    /**
     * Génère un token JWT signé pour un utilisateur donné.
     */
    fun generateToken(userId: String, email: String, role: String): String =
        JWT.create()
            .withAudience(audience)
            .withIssuer(domain)
            .withClaim("userId", userId)
            .withClaim("email", email)
            .withClaim("role", role)
            .withExpiresAt(Date(System.currentTimeMillis() + expiration))
            .sign(algorithm)
}
