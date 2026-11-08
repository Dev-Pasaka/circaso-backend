package com.circaso.identity.data.repositories

import com.circaso.identity.data.tables.ProviderLinksTable
import com.circaso.identity.domain.models.ProviderLink
import com.circaso.identity.domain.repositories.ProviderRepository
import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.api.DatabaseException
import com.circaso.integrations.api.SocialProvider
import com.circaso.shared.UserId
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update

/** Google and Apple logins. The unique `(provider, subject)` index is the claim, settled inside the transaction. */
class ProviderRepositoryImpl(private val database: Database) : ProviderRepository {
    override suspend fun ownerOf(provider: SocialProvider, subject: String): UserId? =
        database.tx("providers.owner-of") {
            ProviderLinksTable
                .select(ProviderLinksTable.userId)
                .where { (ProviderLinksTable.provider eq provider.name) and (ProviderLinksTable.subject eq subject) }
                .map { UserId(it[ProviderLinksTable.userId]) }
                .singleOrNull()
        }

    override suspend fun claim(link: ProviderLink): Boolean =
        database.tx("providers.claim") {
            // ON CONFLICT DO NOTHING: a conflict on either key leaves the transaction usable, unlike a failed insert.
            val inserted =
                ProviderLinksTable
                    .insertIgnore {
                        it[userId] = link.userId.value
                        it[provider] = link.provider.name
                        it[subject] = link.subject
                        it[email] = link.email
                        it[linkedAt] = link.linkedAt
                    }.insertedCount == 1
            when {
                inserted -> true
                else -> afterConflict(link)
            }
        }

    /**
     * Why the insert wrote nothing: the login is already this account's (nothing to do), another account's (lost),
     * or this account already has another login of the same provider, which the new one replaces. A replacement is
     * true only when it wrote the row; it is false when another account's claim for the login won the unique index
     * (which leaves the transaction unusable: the caller answers from `Transactor.attempt`) or when the row it was to
     * replace is gone.
     */
    internal suspend fun afterConflict(link: ProviderLink): Boolean {
        val owner = ownerOf(link.provider, link.subject)
        if (owner != null) return owner == link.userId
        // TODO(decision: whether I12 may replace an account's other Google or Apple login, or must refuse it)
        val replaced =
            try {
                // Its own (joined) tx, so a refused statement arrives here as a DatabaseException.
                database.tx("providers.replace") {
                    ProviderLinksTable.update({
                        (ProviderLinksTable.userId eq link.userId.value) and
                            (ProviderLinksTable.provider eq link.provider.name)
                    }) {
                        it[subject] = link.subject
                        it[email] = link.email
                        it[linkedAt] = link.linkedAt
                    }
                }
            } catch (e: DatabaseException) {
                if (e.isUniqueViolation) return false
                throw e
            }
        return replaced == 1
    }

    override suspend fun links(userId: UserId): List<ProviderLink> =
        database.tx("providers.links") {
            ProviderLinksTable
                .selectAll()
                .where { ProviderLinksTable.userId eq userId.value }
                .map { it.toProviderLink() }
                .toList()
        }

    /** For the store: removes a link inside the open transaction, which frees its login for another account. */
    internal suspend fun remove(link: ProviderLink) {
        database.tx("providers.remove") {
            ProviderLinksTable.deleteWhere {
                (ProviderLinksTable.userId eq link.userId.value) and (ProviderLinksTable.provider eq link.provider.name)
            }
        }
    }
}

internal fun ResultRow.toProviderLink() =
    ProviderLink(
        userId = UserId(this[ProviderLinksTable.userId]),
        provider = SocialProvider.valueOf(this[ProviderLinksTable.provider]),
        subject = this[ProviderLinksTable.subject],
        email = this[ProviderLinksTable.email],
        linkedAt = this[ProviderLinksTable.linkedAt],
    )
