package com.circaso.users.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.domain.repositories.BlockRepository

/** For messaging: has either person blocked the other? A block beats every other tie, a shared group included. */
class CheckBlockedEitherWay(private val blocks: BlockRepository) {
    suspend operator fun invoke(a: UserId, b: UserId): Resource<Boolean> = Resource.success(blocks.eitherWay(a, b))
}
