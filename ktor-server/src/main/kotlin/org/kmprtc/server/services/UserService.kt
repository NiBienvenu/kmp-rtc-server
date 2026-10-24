package org.kmprtc.server.services

import org.kmprtc.server.models.UpdateUserRequest
import org.kmprtc.server.models.User
import org.kmprtc.server.repositories.UserRepository

class UserService(private val userRepository: UserRepository) {

    suspend fun getAll(): List<User> =
        userRepository.findAll()

    suspend fun getById(id: String): User =
        userRepository.findById(id)
            ?: throw NoSuchElementException("Utilisateur '$id' introuvable")

    suspend fun update(id: String, request: UpdateUserRequest): User {
        // Vérifier que l'utilisateur existe avant la mise à jour
        userRepository.findById(id)
            ?: throw NoSuchElementException("Utilisateur '$id' introuvable")

        return userRepository.update(id, request)
            ?: throw IllegalStateException("Mise à jour échouée")
    }

    suspend fun delete(id: String): Boolean {
        userRepository.findById(id)
            ?: throw NoSuchElementException("Utilisateur '$id' introuvable")

        return userRepository.delete(id)
    }
}
