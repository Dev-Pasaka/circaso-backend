package com.circaso.users.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.domain.models.HandleAvailability
import com.circaso.users.domain.models.HandleCheck
import com.circaso.users.domain.repositories.HandleRepository
import com.circaso.users.domain.support.HandleRules

/** U5: free, taken or invalid, with a suggestion when taken. A handle on hold for its old owner is taken for everyone else. */
class CheckHandle(private val handles: HandleRepository) {
    suspend operator fun invoke(userId: UserId, raw: String): Resource<HandleCheck> {
        val handle = HandleRules.normalise(raw) ?: return Resource.success(HandleCheck(raw.lowercase(), HandleAvailability.INVALID, null))
        if (isFreeFor(userId, handle)) return Resource.success(HandleCheck(handle, HandleAvailability.AVAILABLE, null))
        val suggestion = HandleRules.suggestions(handle).take(SUGGESTIONS_TRIED).firstOrNull { isFreeFor(userId, it) }
        return Resource.success(HandleCheck(handle, HandleAvailability.TAKEN, suggestion))
    }

    private suspend fun isFreeFor(userId: UserId, handle: String): Boolean {
        val claim = handles.find(handle) ?: return true
        return claim.userId == userId
    }

    private companion object {
        const val SUGGESTIONS_TRIED = 8
    }
}
