package org.kmprtc.server.models

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Table

// ─── Table Exposed (schéma SQL) ──────────────────────────────────────────────

object UserTable : Table("users") {
    val id          = varchar("id", 36)           // UUID
    val displayName = varchar("display_name", 100)
    val email       = varchar("email", 255).uniqueIndex()
    val phone       = varchar("phone", 20)
    val role        = varchar("role", 50).default("user")
    val status      = varchar("status", 50).default("active")
    val lastSeen    = varchar("last_seen", 50).default("")
    val password    = varchar("password", 255)    // hash BCrypt

    override val primaryKey = PrimaryKey(id)
}

// ─── Domain model (data class) ────────────────────────────────────────────────

@Serializable
data class User(
    val id: String,
    val displayName: String,
    val email: String,
    val phone: String,
    val role: String,
    val status: String,
    val lastSeen: String,
    val password: String? = null        // null dans les réponses API
)

// ─── DTOs ─────────────────────────────────────────────────────────────────────

@Serializable
data class RegisterRequest(
    val displayName: String,
    val email: String,
    val phone: String,
    val password: String,
    val role: String = "user"
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class UpdateUserRequest(
    val displayName: String? = null,
    val phone: String? = null,
    val role: String? = null,
    val status: String? = null
)

@Serializable
data class AuthResponse(
    val token: String,
    val user: User
)

@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val message: String? = null,
    val data: T? = null
)
