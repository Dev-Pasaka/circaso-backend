package com.circaso.users.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.api.PublicProfile
import com.circaso.users.domain.models.ContactStatus
import com.circaso.users.domain.repositories.ContactRepository
import com.circaso.users.domain.repositories.ProfileRepository
import com.circaso.users.domain.support.ProfileViews

/** For the assistant: contacts whose name or handle contains the text, so "Mum" can become a person. */
class SearchContacts(
    private val contacts: ContactRepository,
    private val profiles: ProfileRepository,
    private val views: ProfileViews,
) {
    suspend operator fun invoke(
        userId: UserId,
        query: String,
        limit: Int,
    ): Resource<List<PublicProfile>> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return Resource.success(emptyList())
        val ids =
            contacts
                .list(userId, null, SEARCHED)
                .filter {
                    it.status == ContactStatus.ACTIVE
                }.map { it.contactId }
                .toSet()
        val matches =
            profiles
                .findAll(ids)
                .values
                .filter { needle in it.displayName.lowercase() || it.handle?.contains(needle) == true }
                .map { views.forViewer(userId, it) }
                .sortedBy { it.displayName }
        return Resource.success(matches.take(limit.coerceIn(1, 50)))
    }

    private companion object {
        const val SEARCHED = 1_000
    }
}
