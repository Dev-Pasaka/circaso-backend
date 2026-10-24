package com.circaso.users.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.domain.support.ReachRule

/** For messaging, wallet and calls: may A reach B? */
class CanReach(private val reach: ReachRule) {
    suspend operator fun invoke(from: UserId, to: UserId): Resource<Boolean> =
        Resource.success(reach.canReach(from, to))
}
