package com.circaso.users.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.domain.repositories.ContactRepository

/** `ContactBook.contactsOf`: the ids of a person's active contacts, at most [invoke]'s limit (users:api). */
class ListContactIds(private val contacts: ContactRepository) {
    suspend operator fun invoke(userId: UserId, limit: Int): Resource<Set<UserId>> {
        val size = limit.coerceIn(1, MAX)
        return Resource.success(contacts.list(userId, after = null, limit = size, activeOnly = true).map { it.contactId }.toSet())
    }

    private companion object {
        const val MAX = 10_000
    }
}
