package com.circaso.identity.data.repositories

import com.circaso.identity.data.tables.SupportRecoveriesTable
import com.circaso.identity.domain.models.RecoveryStatus
import com.circaso.identity.domain.models.SupportRecovery
import com.circaso.identity.domain.repositories.RecoveryRepository
import com.circaso.infrastructure.api.Database
import com.circaso.integrations.api.SocialProvider
import com.circaso.shared.UserId
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.upsert
import java.util.UUID

/** Support cases, found by their request id, which is unique in the one table that holds them. */
class RecoveryRepositoryImpl(private val database: Database) : RecoveryRepository {
    override suspend fun find(requestId: UUID): SupportRecovery? =
        database.tx("recoveries.find") {
            SupportRecoveriesTable
                .selectAll()
                .where { SupportRecoveriesTable.requestId eq requestId }
                .map { it.toRecovery() }
                .singleOrNull()
        }

    /** For the store: writes the case as it is now, inside the open transaction. */
    internal suspend fun save(recovery: SupportRecovery) {
        database.tx("recoveries.save") {
            SupportRecoveriesTable.upsert(SupportRecoveriesTable.requestId) {
                it[requestId] = recovery.requestId
                it[userId] = recovery.userId.value
                it[status] = recovery.status.name
                it[contactEmail] = recovery.contactEmail
                it[note] = recovery.note
                it[idCheck] = recovery.idCheck
                it[selfieMatch] = recovery.selfieMatch
                it[agentId] = recovery.agentId
                it[noticeSentAt] = recovery.noticeSentAt
                it[relinkAfter] = recovery.relinkAfter
                it[newProvider] = recovery.newProvider?.name
                it[newSubject] = recovery.newSubject
            }
        }
    }
}

private fun ResultRow.toRecovery() =
    SupportRecovery(
        userId = UserId(this[SupportRecoveriesTable.userId]),
        requestId = this[SupportRecoveriesTable.requestId],
        status = RecoveryStatus.valueOf(this[SupportRecoveriesTable.status]),
        contactEmail = this[SupportRecoveriesTable.contactEmail],
        note = this[SupportRecoveriesTable.note],
        idCheck = this[SupportRecoveriesTable.idCheck],
        selfieMatch = this[SupportRecoveriesTable.selfieMatch],
        agentId = this[SupportRecoveriesTable.agentId],
        noticeSentAt = this[SupportRecoveriesTable.noticeSentAt],
        relinkAfter = this[SupportRecoveriesTable.relinkAfter],
        newProvider = this[SupportRecoveriesTable.newProvider]?.let(SocialProvider::valueOf),
        newSubject = this[SupportRecoveriesTable.newSubject],
    )
