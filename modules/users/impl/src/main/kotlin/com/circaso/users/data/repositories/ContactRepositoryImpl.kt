package com.circaso.users.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.shared.UserId
import com.circaso.users.data.tables.ContactsTable
import com.circaso.users.domain.models.Contact
import com.circaso.users.domain.models.ContactSource
import com.circaso.users.domain.models.ContactStatus
import com.circaso.users.domain.repositories.ContactRepository
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import org.jetbrains.exposed.v1.r2dbc.upsert
import java.time.Instant
import java.util.UUID

/** One table read from both sides: by owner through the primary key, by target through `contacts_target_idx`. */
class ContactRepositoryImpl(private val database: Database) : ContactRepository {
    override suspend fun find(ownerId: UserId, contactId: UserId): Contact? =
        database.tx("contacts.find") {
            ContactsTable
                .selectAll()
                .where { (ContactsTable.ownerId eq ownerId.value) and (ContactsTable.contactId eq contactId.value) }
                .singleOrNull()
                ?.toContact()
        }

    override suspend fun findAll(ownerId: UserId, contactIds: Set<UserId>): Map<UserId, Contact> {
        if (contactIds.isEmpty()) return emptyMap()
        return database.tx("contacts.find-all") {
            ContactsTable
                .selectAll()
                .where {
                    (ContactsTable.ownerId eq ownerId.value) and
                        (ContactsTable.contactId inList contactIds.map { it.value })
                }.toList()
                .map { it.toContact() }
                .associateBy { it.contactId }
        }
    }

    override suspend fun list(
        ownerId: UserId,
        after: UUID?,
        limit: Int,
        activeOnly: Boolean,
    ): List<Contact> =
        database.tx("contacts.list") {
            ContactsTable
                .selectAll()
                .where {
                    var condition: Op<Boolean> = ContactsTable.ownerId eq ownerId.value
                    if (after != null) condition = condition and (ContactsTable.contactId greater after)
                    if (activeOnly) condition = condition and (ContactsTable.status eq ContactStatus.ACTIVE.name)
                    condition
                }.orderBy(ContactsTable.contactId to SortOrder.ASC)
                .limit(limit)
                .toList()
                .map { it.toContact() }
        }

    override suspend fun ownersOf(contactId: UserId, limit: Int): List<UserId> =
        database.tx("contacts.owners-of") {
            ContactsTable
                .selectAll()
                .where {
                    (ContactsTable.contactId eq contactId.value) and
                        (ContactsTable.status eq ContactStatus.ACTIVE.name)
                }.orderBy(ContactsTable.ownerId to SortOrder.ASC)
                .limit(limit)
                .toList()
                .map { UserId(it[ContactsTable.ownerId]) }
        }

    /**
     * `INSERT … ON CONFLICT (owner_id, contact_id) DO UPDATE … WHERE status <> 'ACTIVE'`: an active row is left alone
     * and counts as no change; a removed one comes back with the new source and its first `added_at`.
     */
    override suspend fun connect(contact: Contact): Boolean =
        database.tx("contacts.connect") {
            val statement =
                ContactsTable.upsert(
                    ContactsTable.ownerId,
                    ContactsTable.contactId,
                    onUpdateExclude = listOf(ContactsTable.addedAt),
                    where = { ContactsTable.status neq ContactStatus.ACTIVE.name },
                ) {
                    it[ownerId] = contact.ownerId.value
                    it[contactId] = contact.contactId.value
                    it[contactSource] = contact.source.name
                    it[status] = ContactStatus.ACTIVE.name
                    it[removedAt] = null
                    it[addedAt] = contact.addedAt
                }
            statement.insertedCount > 0
        }

    override suspend fun remove(
        ownerId: UserId,
        contactId: UserId,
        at: Instant,
    ): Boolean =
        database.tx("contacts.remove") {
            val updated =
                ContactsTable.update({
                    (ContactsTable.ownerId eq ownerId.value) and
                        (ContactsTable.contactId eq contactId.value) and
                        (ContactsTable.status eq ContactStatus.ACTIVE.name)
                }) {
                    it[status] = ContactStatus.REMOVED.name
                    it[removedAt] = at
                }
            updated == 1
        }
}

private fun ResultRow.toContact() =
    Contact(
        ownerId = UserId(this[ContactsTable.ownerId]),
        contactId = UserId(this[ContactsTable.contactId]),
        source = ContactSource.valueOf(this[ContactsTable.contactSource]),
        status = ContactStatus.valueOf(this[ContactsTable.status]),
        removedAt = this[ContactsTable.removedAt],
        addedAt = this[ContactsTable.addedAt],
    )
