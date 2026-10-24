package org.kmprtc.server.repositories

import org.kmprtc.server.models.User
import org.kmprtc.server.models.UpdateUserRequest

// ─── Interface (contrat) ──────────────────────────────────────────────────────

interface UserRepository {
    suspend fun findAll(): List<User>
    suspend fun findById(id: String): User?
    suspend fun findByEmail(email: String): User?          // retourne le hash inclus
    suspend fun create(user: User): User
    suspend fun update(id: String, request: UpdateUserRequest): User?
    suspend fun delete(id: String): Boolean
    suspend fun updateLastSeen(id: String, lastSeen: String)
}
