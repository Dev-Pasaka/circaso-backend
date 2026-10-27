package com.circaso.users.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.users.data.tables.BlocksTable
import com.circaso.users.data.tables.ContactsTable
import com.circaso.users.data.tables.HandlesTable
import com.circaso.users.data.tables.InvitesTable
import com.circaso.users.data.tables.ProfilesTable
import com.circaso.users.data.tables.WaitingContactsTable
import com.circaso.users.domain.models.Contact
import com.circaso.users.domain.models.HandleClaim
import com.circaso.users.domain.models.WaitingContact
import com.circaso.users.domain.repositories.UsersChanges
import com.circaso.users.domain.repositories.UsersStore
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.BatchInsertStatement
import org.jetbrains.exposed.v1.r2dbc.batchInsert
import org.jetbrains.exposed.v1.r2dbc.batchUpsert
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.update
import java.util.UUID

/**
 * Writes one operation's rows and its outbox rows in one transaction: the use case's own when it has one open,
 * otherwise a transaction of its own. Publishing is the caller's, after the commit.
 */
class UsersStoreImpl(private val database: Database) : UsersStore {
    override suspend fun commit(changes: UsersChanges) {
        if (changes == UsersChanges()) return
        database.tx("users.commit") {
            addContacts(changes.contacts)
            addInvites(changes)
            keepWaiting(changes.waiting)
            changes.clearedWaiting.forEach { clearWaiting(it) }
            changes.releasedHandles.forEach { release(it) }
            if (changes.erasedUsers.isNotEmpty()) erase(changes.erasedUsers.map { it.value })
            changes.events.forEach { database.writeOutbox(it) }
        }
    }

    /** `ON CONFLICT DO NOTHING`: a pair that has a row, removed or not, keeps it. */
    private suspend fun addContacts(contacts: List<Contact>) {
        if (contacts.isEmpty()) return
        ContactsTable.batchInsert(contacts, ignore = true, shouldReturnGeneratedValues = false) { write(it) }
    }

    private fun BatchInsertStatement.write(contact: Contact) {
        this[ContactsTable.ownerId] = contact.ownerId.value
        this[ContactsTable.contactId] = contact.contactId.value
        this[ContactsTable.contactSource] = contact.source.name
        this[ContactsTable.status] = contact.status.name
        this[ContactsTable.removedAt] = contact.removedAt
        this[ContactsTable.addedAt] = contact.addedAt
    }

    private suspend fun addInvites(changes: UsersChanges) {
        if (changes.invites.isEmpty()) return
        InvitesTable.batchInsert(changes.invites, shouldReturnGeneratedValues = false) {
            this[InvitesTable.token] = it.token
            this[InvitesTable.ownerId] = it.ownerId.value
            this[InvitesTable.kind] = it.kind
            this[InvitesTable.createdAt] = it.createdAt
            this[InvitesTable.expiresAt] = it.expiresAt
        }
    }

    /** A number synced again is kept for another 180 days from now. */
    private suspend fun keepWaiting(waiting: List<WaitingContact>) {
        if (waiting.isEmpty()) return
        WaitingContactsTable.batchUpsert(
            waiting,
            WaitingContactsTable.phoneHmac,
            WaitingContactsTable.ownerId,
            shouldReturnGeneratedValues = false,
        ) {
            this[WaitingContactsTable.phoneHmac] = it.phoneHmac
            this[WaitingContactsTable.ownerId] = it.ownerId.value
            this[WaitingContactsTable.addedAt] = it.addedAt
            this[WaitingContactsTable.expiresAt] = it.addedAt.plus(WAITING_TTL)
        }
    }

    private suspend fun clearWaiting(row: WaitingContact) {
        WaitingContactsTable.deleteWhere { (phoneHmac eq row.phoneHmac) and (ownerId eq row.ownerId.value) }
    }

    /** Puts the old owner's handle on its 30-day hold; a row someone else has since taken is left alone. */
    private suspend fun release(claim: HandleClaim) {
        val releasedAt = claim.releasedAt ?: return
        HandlesTable.update({ (HandlesTable.handle eq claim.handle) and (HandlesTable.userId eq claim.userId.value) }) {
            it[this.releasedAt] = releasedAt
            it[expiresAt] = releasedAt.plus(HANDLE_HOLD)
        }
    }

    /** A deleted account: its profile, its contacts and blocks in both directions, and its invites. */
    private suspend fun erase(ids: List<UUID>) {
        ProfilesTable.deleteWhere { userId inList ids }
        ContactsTable.deleteWhere { (ownerId inList ids) or (contactId inList ids) }
        BlocksTable.deleteWhere { (userId inList ids) or (blockedId inList ids) }
        InvitesTable.deleteWhere { ownerId inList ids }
    }
}
