package org.kmprtc.server.services

import org.kmprtc.server.models.AuthResponse
import org.kmprtc.server.models.LoginRequest
import org.kmprtc.server.models.RegisterRequest
import org.kmprtc.server.models.User
import org.kmprtc.server.config.JwtConfig
import org.kmprtc.server.repositories.UserRepository
import org.mindrot.jbcrypt.BCrypt
import java.time.Instant
import java.util.UUID

class AuthService(private val userRepository: UserRepository) {

    // ─── Register ─────────────────────────────────────────────────────────────
    // trustedRole=false (API publique): le role envoye par le client est ignore -> "user".
    // Seul le code serveur (ex: creation du compte admin au demarrage) peut fixer un autre role.
    suspend fun register(request: RegisterRequest, trustedRole: Boolean = false): User {
        // Vérification email unique
        userRepository.findByEmail(request.email)?.let {
            throw IllegalArgumentException("Email déjà utilisé")
        }

        val hashedPassword = BCrypt.hashpw(request.password, BCrypt.gensalt(12))

        val newUser = User(
            id = UUID.randomUUID().toString(),
            displayName = request.displayName,
            email = request.email,
            phone = request.phone,
            role = if (trustedRole) request.role else "user",
            status = "active",
            lastSeen = Instant.now().toString(),
            password = hashedPassword
        )

        return userRepository.create(newUser)
    }

    // ─── Login ────────────────────────────────────────────────────────────────
    suspend fun login(request: LoginRequest): AuthResponse {
        val user = userRepository.findByEmail(request.email)
            ?: throw IllegalArgumentException("Email ou mot de passe incorrect")

        if (user.status == "banned") {
            throw IllegalStateException("Compte suspendu")
        }

        val passwordHash = user.password
            ?: throw IllegalStateException("Erreur interne")

        if (!BCrypt.checkpw(request.password, passwordHash)) {
            throw IllegalArgumentException("Email ou mot de passe incorrect")
        }

        // Mise à jour du lastSeen
        val now = Instant.now().toString()
        userRepository.updateLastSeen(user.id, now)

        val token = JwtConfig.generateToken(user.id, user.email, user.role)

        return AuthResponse(
            token = token,
            user = user.copy(password = null, lastSeen = now)
        )
    }

    // ─── Logout ───────────────────────────────────────────────────────────────
    fun logout(token: String) {
        TokenService.invalidate(token)
    }
}
