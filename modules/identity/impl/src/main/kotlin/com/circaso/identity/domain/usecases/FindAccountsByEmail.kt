package com.circaso.identity.domain.usecases

import com.circaso.identity.domain.repositories.AccountAdminRepository
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/**
 * Staff search by the email of a Google or Apple sign-in (decision 2026-11-08), case-insensitively. A Google and an
 * Apple login may share one address, and two accounts may each have one with it, so the answer is a list.
 */
class FindAccountsByEmail(private val admin: AccountAdminRepository) {
    suspend operator fun invoke(email: String): Resource<List<UserId>> {
        val wanted = email.trim()
        if (wanted.isEmpty()) return Resource.success(emptyList())
        return Resource.success(admin.byEmail(wanted, MAX_MATCHES))
    }

    private companion object {
        /** One person rarely has more than two; the cap only bounds an odd address shared by many. */
        const val MAX_MATCHES = 50
    }
}
