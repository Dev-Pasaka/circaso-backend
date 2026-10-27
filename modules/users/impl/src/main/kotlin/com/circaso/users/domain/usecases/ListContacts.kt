package com.circaso.users.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.users.domain.models.ContactEntry
import com.circaso.users.domain.models.ContactPage
import com.circaso.users.domain.models.ContactStatus
import com.circaso.users.domain.repositories.ContactRepository
import com.circaso.users.domain.repositories.ProfileRepository
import com.circaso.users.domain.support.ProfileViews
import com.circaso.users.domain.support.fallbackProfile
import java.util.UUID

/** U8: the user's contacts with public profiles, paged by an opaque cursor. */
class ListContacts(
    private val contacts: ContactRepository,
    private val profiles: ProfileRepository,
    private val views: ProfileViews,
) {
    suspend operator fun invoke(
        userId: UserId,
        cursor: String?,
        limit: Int,
    ): Resource<ContactPage> {
        val after =
            cursor?.let {
                runCatching { UUID.fromString(it) }.getOrNull()
                    ?: return Resource.error(SharedError.Malformed("cursor", "invalid"))
            }
        val size = limit.coerceIn(1, 200)

        // Removed contacts stay in the table (so a sync doesn't add them back); skip them here.
        val rows = contacts.list(userId, after, size * 3)
        val active = rows.filter { it.status == ContactStatus.ACTIVE }
        val page = active.take(size)
        val more = active.size > size || (rows.size == size * 3 && active.size >= size)
        val found = profiles.findAll(page.map { it.contactId }.toSet())
        val entries =
            page.map {
                ContactEntry(
                    it,
                    found[it.contactId]?.let { p -> views.forViewer(userId, p) } ?: fallbackProfile(it.contactId),
                )
            }
        return Resource.success(
            ContactPage(
                entries,
                if (more) {
                    page
                        .last()
                        .contactId.value
                        .toString()
                } else {
                    null
                },
            ),
        )
    }
}
