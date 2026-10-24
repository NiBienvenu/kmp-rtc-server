package org.kmprtc.server.repositories

import org.kmprtc.server.config.DatabaseFactory.dbQuery
import org.kmprtc.server.models.UpdateUserRequest
import org.kmprtc.server.models.User
import org.kmprtc.server.models.UserTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

class UserRepositoryImpl : UserRepository {

    // ─── Mapper ResultRow → User ──────────────────────────────────────────────
    private fun rowToUser(row: ResultRow, includePassword: Boolean = false) = User(
        id          = row[UserTable.id],
        displayName = row[UserTable.displayName],
        email       = row[UserTable.email],
        phone       = row[UserTable.phone],
        role        = row[UserTable.role],
        status      = row[UserTable.status],
        lastSeen    = row[UserTable.lastSeen],
        password    = if (includePassword) row[UserTable.password] else null
    )

    override suspend fun findAll(): List<User> = dbQuery {
        UserTable.selectAll()
            .orderBy(UserTable.displayName)
            .map { rowToUser(it) }
    }

    override suspend fun findById(id: String): User? = dbQuery {
        UserTable.select { UserTable.id eq id }
            .singleOrNull()
            ?.let { rowToUser(it) }
    }

    override suspend fun findByEmail(email: String): User? = dbQuery {
        UserTable.select { UserTable.email eq email }
            .singleOrNull()
            ?.let { rowToUser(it, includePassword = true) }
    }

    override suspend fun create(user: User): User = dbQuery {
        UserTable.insert {
            it[id]          = user.id
            it[displayName] = user.displayName
            it[email]       = user.email
            it[phone]       = user.phone
            it[role]        = user.role
            it[status]      = user.status
            it[lastSeen]    = user.lastSeen
            it[password]    = user.password ?: error("Password required for creation")
        }
        rowToUser(UserTable.select { UserTable.id eq user.id }.single())
    }

    override suspend fun update(id: String, request: UpdateUserRequest): User? = dbQuery {
        val updated = UserTable.update({ UserTable.id eq id }) {
            request.displayName?.let { v -> it[displayName] = v }
            request.phone?.let      { v -> it[phone]       = v }
            request.role?.let       { v -> it[role]        = v }
            request.status?.let     { v -> it[status]      = v }
        }
        if (updated == 0) null
        else UserTable.select { UserTable.id eq id }.singleOrNull()?.let { rowToUser(it) }
    }

    override suspend fun delete(id: String): Boolean = dbQuery {
        UserTable.deleteWhere { UserTable.id eq id } > 0
    }

    override suspend fun updateLastSeen(id: String, lastSeen: String): Unit = dbQuery {
        UserTable.update({ UserTable.id eq id }) {
            it[UserTable.lastSeen] = lastSeen
        }
    }
}
