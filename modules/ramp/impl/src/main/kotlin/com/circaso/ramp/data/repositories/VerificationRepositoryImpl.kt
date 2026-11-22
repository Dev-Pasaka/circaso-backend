package com.circaso.ramp.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.api.DatabaseException
import com.circaso.integrations.api.KycEnvironment
import com.circaso.ramp.data.tables.BlocksTable
import com.circaso.ramp.data.tables.VerificationsTable
import com.circaso.ramp.domain.models.Block
import com.circaso.ramp.domain.models.BlockReason
import com.circaso.ramp.domain.models.PageCursor
import com.circaso.ramp.domain.models.Subject
import com.circaso.ramp.domain.models.SubjectKind
import com.circaso.ramp.domain.models.Verification
import com.circaso.ramp.domain.models.VerificationChange
import com.circaso.ramp.domain.models.VerificationReason
import com.circaso.ramp.domain.models.VerificationStatus
import com.circaso.ramp.domain.models.VerifiedOutcome
import com.circaso.ramp.domain.repositories.BlockRepository
import com.circaso.ramp.domain.repositories.VerificationRepository
import com.circaso.shared.UserId
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.deleteReturning
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import org.jetbrains.exposed.v1.r2dbc.updateReturning
import org.jetbrains.exposed.v1.r2dbc.upsert
import java.time.Instant
import java.util.UUID

/**
 * `ramp.verifications`. Outcomes move a row by `updateReturning` guarded on its current session, so a webhook, the
 * sweep and the app's own status read can't apply the same outcome twice, and an old session's late news can't touch
 * a newer one. `verifications_identity_key` is what refuses a second account on one identity.
 */
class VerificationRepositoryImpl(private val database: Database) : VerificationRepository {
    override suspend fun find(subject: Subject, environment: KycEnvironment): Verification? =
        database.tx("verifications.find") { one { row(subject, environment) } }

    override suspend fun bySession(sessionId: String): Verification? =
        database.tx("verifications.by-session") { one { VerificationsTable.sessionId eq sessionId } }

    override suspend fun ofSubject(subject: Subject): List<Verification> =
        database.tx("verifications.of-subject") {
            VerificationsTable
                .selectAll()
                .where {
                    (VerificationsTable.subjectKind eq subject.kind.name) and
                        (VerificationsTable.subjectId eq subject.id)
                }.map { it.toVerification() }
                .toList()
                .sortedBy { it.environment }
        }

    override suspend fun save(verification: Verification) {
        database.tx("verifications.save") {
            VerificationsTable.upsert(
                keys =
                    arrayOf(
                        VerificationsTable.subjectKind,
                        VerificationsTable.subjectId,
                        VerificationsTable.environment,
                    ),
                // An earlier approval's identity key and the day the row was first made stay as they are.
                onUpdateExclude = listOf(VerificationsTable.identityKey, VerificationsTable.createdAt),
            ) {
                it[subjectKind] = verification.subject.kind.name
                it[subjectId] = verification.subject.id
                it[environment] = verification.environment.name
                it[status] = verification.status.name
                it[reason] = verification.reason?.code
                it[provider] = verification.provider
                it[sessionId] = verification.sessionId
                it[sessionToken] = verification.sessionToken
                it[sessionUrl] = verification.sessionUrl
                it[sessionExpiresAt] = verification.sessionExpiresAt
                it[identityKey] = verification.identityKey
                it[attempts] = verification.attempts
                it[attemptDay] = verification.attemptDay
                it[attemptsOnDay] = verification.attemptsOnDay
                it[startedAt] = verification.startedAt
                it[checkedAt] = verification.checkedAt
                it[verifiedAt] = verification.verifiedAt
                it[createdAt] = verification.createdAt
                it[updatedAt] = verification.updatedAt
            }
        }
    }

    override suspend fun move(
        subject: Subject,
        environment: KycEnvironment,
        sessionId: String,
        change: VerificationChange,
        at: Instant,
    ): Verification? =
        database.tx("verifications.move") {
            VerificationsTable
                .updateReturning(where = { onSession(subject, environment, sessionId) and isNot(change) }) {
                    it[status] = change.status.name
                    it[reason] = change.reason?.code
                    it[checkedAt] = at
                    it[updatedAt] = at
                }.map { it.toVerification() }
                .singleOrNull()
        }

    override suspend fun verify(
        subject: Subject,
        environment: KycEnvironment,
        sessionId: String,
        identityKey: String?,
        at: Instant,
    ): VerifiedOutcome =
        try {
            val verified = VerificationChange(VerificationStatus.VERIFIED)
            database.tx("verifications.verify") {
                VerificationsTable
                    .updateReturning(where = { onSession(subject, environment, sessionId) and isNot(verified) }) {
                        it[status] = VerificationStatus.VERIFIED.name
                        it[reason] = null
                        if (identityKey != null) it[VerificationsTable.identityKey] = identityKey
                        it[sessionToken] = null
                        it[sessionUrl] = null
                        it[sessionExpiresAt] = null
                        it[verifiedAt] = at
                        it[checkedAt] = at
                        it[updatedAt] = at
                    }.map { it.toVerification() }
                    .singleOrNull()
                    ?.let { VerifiedOutcome.Moved(it) }
                    ?: VerifiedOutcome.AlreadyThere
            }
        } catch (e: DatabaseException) {
            // verifications_identity_key: this identity already verified another account of the same kind.
            if (e.isUniqueViolation) VerifiedOutcome.IdentityInUse else throw e
        }

    override suspend fun touch(
        subject: Subject,
        environment: KycEnvironment,
        at: Instant,
    ) {
        database.tx("verifications.touch") {
            VerificationsTable.update({ row(subject, environment) }) { it[checkedAt] = at }
        }
    }

    override suspend fun stale(
        statuses: Set<VerificationStatus>,
        checkedBefore: Instant,
        limit: Int,
    ): List<Verification> =
        database.tx("verifications.stale") {
            VerificationsTable
                .selectAll()
                .where {
                    (VerificationsTable.status inList statuses.map { it.name }) and
                        (VerificationsTable.checkedAt less checkedBefore)
                }.orderBy(VerificationsTable.checkedAt to SortOrder.ASC)
                .limit(limit)
                .map { it.toVerification() }
                .toList()
        }

    override suspend fun statuses(
        kind: SubjectKind,
        ids: Collection<UUID>,
        environment: KycEnvironment,
    ): Map<UUID, VerificationStatus> {
        if (ids.isEmpty()) return emptyMap()
        return database.tx("verifications.statuses") {
            VerificationsTable
                .selectAll()
                .where {
                    (VerificationsTable.subjectKind eq kind.name) and
                        (VerificationsTable.environment eq environment.name) and
                        (VerificationsTable.subjectId inList ids.toList())
                }.map { it[VerificationsTable.subjectId] to VerificationStatus.valueOf(it[VerificationsTable.status]) }
                .toList()
                .toMap()
        }
    }

    override suspend fun delete(subject: Subject, environment: KycEnvironment): Verification? =
        database.tx("verifications.delete") {
            VerificationsTable
                .deleteReturning(where = { row(subject, environment) })
                .map { it.toVerification() }
                .singleOrNull()
        }

    private suspend fun one(where: () -> Op<Boolean>): Verification? =
        VerificationsTable
            .selectAll()
            .where(where)
            .map { it.toVerification() }
            .singleOrNull()

    private fun row(subject: Subject, environment: KycEnvironment): Op<Boolean> =
        (VerificationsTable.subjectKind eq subject.kind.name) and
            (VerificationsTable.subjectId eq subject.id) and
            (VerificationsTable.environment eq environment.name)

    private fun onSession(
        subject: Subject,
        environment: KycEnvironment,
        sessionId: String,
    ): Op<Boolean> = row(subject, environment) and (VerificationsTable.sessionId eq sessionId)

    /** The row isn't already exactly at [change]: another status, or the same status for another reason. */
    private fun isNot(change: VerificationChange): Op<Boolean> {
        val otherStatus = VerificationsTable.status neq change.status.name
        val reason = change.reason?.code
        return if (reason == null) {
            otherStatus or VerificationsTable.reason.isNotNull()
        } else {
            otherStatus or VerificationsTable.reason.isNull() or (VerificationsTable.reason neq reason)
        }
    }

    private fun ResultRow.toVerification() =
        Verification(
            subject =
                Subject(
                    SubjectKind.valueOf(this[VerificationsTable.subjectKind]),
                    this[VerificationsTable.subjectId],
                ),
            environment = KycEnvironment.valueOf(this[VerificationsTable.environment]),
            status = VerificationStatus.valueOf(this[VerificationsTable.status]),
            reason = VerificationReason.of(this[VerificationsTable.reason]),
            provider = this[VerificationsTable.provider],
            sessionId = this[VerificationsTable.sessionId],
            sessionToken = this[VerificationsTable.sessionToken],
            sessionUrl = this[VerificationsTable.sessionUrl],
            sessionExpiresAt = this[VerificationsTable.sessionExpiresAt],
            identityKey = this[VerificationsTable.identityKey],
            attempts = this[VerificationsTable.attempts],
            attemptDay = this[VerificationsTable.attemptDay],
            attemptsOnDay = this[VerificationsTable.attemptsOnDay],
            startedAt = this[VerificationsTable.startedAt],
            checkedAt = this[VerificationsTable.checkedAt],
            verifiedAt = this[VerificationsTable.verifiedAt],
            createdAt = this[VerificationsTable.createdAt],
            updatedAt = this[VerificationsTable.updatedAt],
        )
}

/** `ramp.blocks`: a claim per user and per identity (`insertIgnore` on each unique key). */
class BlockRepositoryImpl(private val database: Database) : BlockRepository {
    override suspend fun ofUser(userId: UserId): Block? =
        database.tx("blocks.of-user") {
            BlocksTable
                .selectAll()
                .where { BlocksTable.userId eq userId.value }
                .map { it.toBlock() }
                .singleOrNull()
        }

    override suspend fun isBlocked(userId: UserId, identityKey: String?): Boolean =
        database.tx("blocks.is-blocked") {
            val byUser = BlocksTable.userId eq userId.value
            val either = if (identityKey == null) byUser else byUser or (BlocksTable.identityKey eq identityKey)
            BlocksTable
                .selectAll()
                .where { either }
                .limit(1)
                .map { true }
                .singleOrNull() ?: false
        }

    override suspend fun hasIdentity(identityKey: String): Boolean =
        database.tx("blocks.has-identity") {
            BlocksTable
                .selectAll()
                .where { BlocksTable.identityKey eq identityKey }
                .map { true }
                .singleOrNull() ?: false
        }

    override suspend fun add(block: Block): Boolean =
        database.tx("blocks.add") {
            BlocksTable
                .insertIgnore {
                    it[blockId] = block.blockId
                    it[userId] = block.userId?.value
                    it[identityKey] = block.identityKey
                    it[reason] = block.reason.name
                    it[note] = block.note
                    it[sourceUserId] = block.sourceUserId?.value
                    it[createdBy] = block.createdBy
                    it[createdAt] = block.createdAt
                }.insertedCount > 0
        }

    override suspend fun removeUser(userId: UserId): Int =
        database.tx("blocks.remove-user") {
            BlocksTable.deleteWhere {
                (BlocksTable.userId eq userId.value) or (BlocksTable.sourceUserId eq userId.value)
            }
        }

    override suspend fun users(after: PageCursor?, limit: Int): List<Block> =
        database.tx("blocks.users") {
            val listed = BlocksTable.userId.isNotNull()
            val page =
                if (after == null) {
                    listed
                } else {
                    listed and
                        (
                            (BlocksTable.createdAt less after.at) or
                                ((BlocksTable.createdAt eq after.at) and (BlocksTable.blockId less after.id))
                        )
                }
            BlocksTable
                .selectAll()
                .where { page }
                .orderBy(BlocksTable.createdAt to SortOrder.DESC, BlocksTable.blockId to SortOrder.DESC)
                .limit(limit)
                .map { it.toBlock() }
                .toList()
        }

    private fun ResultRow.toBlock() =
        Block(
            blockId = this[BlocksTable.blockId],
            userId = this[BlocksTable.userId]?.let(::UserId),
            identityKey = this[BlocksTable.identityKey],
            reason = BlockReason.valueOf(this[BlocksTable.reason]),
            note = this[BlocksTable.note],
            sourceUserId = this[BlocksTable.sourceUserId]?.let(::UserId),
            createdBy = this[BlocksTable.createdBy],
            createdAt = this[BlocksTable.createdAt],
        )
}
