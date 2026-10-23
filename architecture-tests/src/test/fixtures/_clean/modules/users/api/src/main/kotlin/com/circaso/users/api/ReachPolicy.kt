package com.circaso.users.api

import com.circaso.identity.api.AccountDirectory
import com.circaso.shared.Resource
import com.circaso.shared.UserId

interface ReachPolicy {
    suspend fun canReach(from: UserId, to: UserId): Resource<Boolean>
}
