package com.circaso.users.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.shared.UserId
import com.circaso.users.data.tables.InvitesTable
import com.circaso.users.domain.models.Invite
import com.circaso.users.domain.repositories.InviteRepository
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.r2dbc.selectAll
import java.time.Clock

class InviteRepositoryImpl(private val database: Database, private val clock: Clock) : InviteRepository {
    override suspend fun find(token: String): Invite? =
        database.tx("invites.find") {
            InvitesTable
                .selectAll()
                .where { (InvitesTable.token eq token) and (InvitesTable.expiresAt greater clock.instant()) }
                .singleOrNull()
                ?.let {
                    Invite(
                        it[InvitesTable.token],
                        UserId(it[InvitesTable.ownerId]),
                        it[InvitesTable.kind],
                        it[InvitesTable.createdAt],
                        it[InvitesTable.expiresAt],
                    )
                }
        }
}
