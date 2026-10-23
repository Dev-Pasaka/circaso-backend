package com.circaso.users.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.domain.support.ReachRule

/** U15: whether the caller can reach that person (app hints and debugging). Never says why not. */
class CheckReach(private val reach: ReachRule) {
    suspend operator fun invoke(from: UserId, to: UserId): Resource<Boolean> = Resource.success(reach.canReach(from, to))
}
