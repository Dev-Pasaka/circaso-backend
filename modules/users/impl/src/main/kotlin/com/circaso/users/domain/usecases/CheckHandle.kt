package com.circaso.users.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.users.domain.models.HandleAvailability
import com.circaso.users.domain.models.HandleCheck
import com.circaso.users.domain.ports.UsersCache
import com.circaso.users.domain.repositories.HandleRepository
import com.circaso.users.domain.support.HandleRules

/**
 * U5: free, taken or invalid, with a suggestion when taken. A handle on hold for its old owner is taken for everyone
 * else. Counts against the same 30 handle lookups an hour as U9 and connecting by handle, so it can't be used to
 * walk the handle space.
 */
class CheckHandle(private val handles: HandleRepository, private val usersCache: UsersCache) {
    suspend operator fun invoke(userId: UserId, raw: String): Resource<HandleCheck> {
        if (usersCache.countHandleLookup(userId) > MAX_PER_HOUR) {
            return Resource.error(SharedError.RateLimited(usersCache.handleLookupRetryAfter(userId)?.seconds ?: 3_600))
        }
        val handle =
            HandleRules.normalise(raw)
                ?: return Resource.success(HandleCheck(raw.lowercase(), HandleAvailability.INVALID, null))
        if (isFreeFor(userId, handle)) return Resource.success(HandleCheck(handle, HandleAvailability.AVAILABLE, null))
        val suggestion = HandleRules.suggestions(handle).take(SUGGESTIONS_TRIED).firstOrNull { isFreeFor(userId, it) }
        return Resource.success(HandleCheck(handle, HandleAvailability.TAKEN, suggestion))
    }

    private suspend fun isFreeFor(userId: UserId, handle: String): Boolean {
        val claim = handles.find(handle) ?: return true
        return claim.userId == userId
    }

    private companion object {
        const val MAX_PER_HOUR = 30
        const val SUGGESTIONS_TRIED = 8
    }
}
