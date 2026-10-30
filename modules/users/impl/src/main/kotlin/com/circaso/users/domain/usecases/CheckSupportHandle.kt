package com.circaso.users.domain.usecases

import com.circaso.shared.Resource
import com.circaso.users.domain.errors.UsersError
import com.circaso.users.domain.repositories.HandleRepository
import com.circaso.users.domain.support.HandleRules

/**
 * Identity's `SupportProfiles.checkHandle`, before ops creates a support account (decision 2026-10-31): the handle in
 * its stored form if it is well formed and free. Reserved names are allowed, because only a support account may hold
 * one. Nothing is held; [CreateSupportProfile] claims it once the account exists.
 */
class CheckSupportHandle(private val handles: HandleRepository) {
    suspend operator fun invoke(raw: String): Resource<String> {
        val handle = HandleRules.normalise(raw, allowReserved = true) ?: return Resource.error(UsersError.HandleInvalid)
        if (handles.find(handle) != null) return Resource.error(UsersError.HandleTaken)
        return Resource.success(handle)
    }
}
