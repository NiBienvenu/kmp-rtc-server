package org.kmprtc.server.services

import java.util.concurrent.ConcurrentHashMap

/**
 * Service de blacklist de tokens (logout).
 * En production, remplacer par Redis ou une table DB pour la persistance.
 */
object TokenService {

    private val blacklist = ConcurrentHashMap.newKeySet<String>()

    fun invalidate(token: String) {
        blacklist.add(token)
    }

    fun isBlacklisted(token: String): Boolean = blacklist.contains(token)
}
