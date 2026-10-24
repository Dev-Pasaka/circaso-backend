package com.circaso.users.presentation.services

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.api.PublicProfile
import com.circaso.users.domain.models.ConnectVia
import com.circaso.users.domain.models.Connection
import com.circaso.users.domain.models.ContactPage
import com.circaso.users.domain.models.InviteLink
import com.circaso.users.domain.models.SyncCommand
import com.circaso.users.domain.models.SyncMatch
import com.circaso.users.domain.usecases.Connect
import com.circaso.users.domain.usecases.CreateInvite
import com.circaso.users.domain.usecases.ListContacts
import com.circaso.users.domain.usecases.RemoveContact
import com.circaso.users.domain.usecases.RevokeInvite
import com.circaso.users.domain.usecases.SearchContacts
import com.circaso.users.domain.usecases.SyncContacts

/** Contacts: sync, list, connect, invites, remove, and the assistant's read-only views. */
class ContactService(
    private val syncContacts: SyncContacts,
    private val listContacts: ListContacts,
    private val connect: Connect,
    private val createInvite: CreateInvite,
    private val revokeInvite: RevokeInvite,
    private val removeContact: RemoveContact,
    private val searchContacts: SearchContacts,
) {
    suspend fun sync(command: SyncCommand): Resource<List<SyncMatch>> = syncContacts(command)

    suspend fun listContacts(
        userId: UserId,
        cursor: String?,
        limit: Int,
    ): Resource<ContactPage> =
        listContacts.invoke(
            userId,
            cursor,
            limit,
        )

    suspend fun connect(
        viewer: UserId,
        via: ConnectVia,
        value: String,
    ): Resource<Connection> =
        connect.invoke(
            viewer,
            via,
            value,
        )

    suspend fun createInvite(userId: UserId): Resource<InviteLink> = createInvite.invoke(userId)

    suspend fun revokeInvite(userId: UserId, token: String): Resource<Unit> = revokeInvite.invoke(userId, token)

    suspend fun remove(userId: UserId, contactId: UserId): Resource<Unit> = removeContact(userId, contactId)

    suspend fun findContacts(
        userId: UserId,
        query: String,
        limit: Int,
    ): Resource<List<PublicProfile>> =
        searchContacts(
            userId,
            query,
            limit,
        )
}
