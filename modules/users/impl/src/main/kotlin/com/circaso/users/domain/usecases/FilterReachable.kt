package com.circaso.users.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.domain.support.ReachRule

/** For messaging, wallet and calls: which of these people may A reach? */
class FilterReachable(private val reach: ReachRule) {
    suspend operator fun invoke(from: UserId, to: Set<UserId>): Resource<Set<UserId>> =
        Resource.success(reach.reachable(from, to))
}
