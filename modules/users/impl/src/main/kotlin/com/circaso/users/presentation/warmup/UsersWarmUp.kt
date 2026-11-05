package com.circaso.users.presentation.warmup

import com.circaso.infrastructure.api.WarmUp
import com.circaso.shared.UserId
import com.circaso.users.api.Profiles
import com.circaso.users.api.ReachPolicy
import com.circaso.users.domain.ports.AvatarStorage
import com.circaso.users.domain.repositories.ProfileRepository

/**
 * Users' hot paths, read-only, for random ids: the profiles other modules ask for (every chat list), one profile, the
 * block check behind a send, and one avatar link (the first presign builds the storage client's signer). Real profiles
 * with pictures are read through messaging's warm-up, which lists real chats. Not `canReach`: it caches its answer.
 */
class UsersWarmUp(
    private val profiles: Profiles,
    private val repository: ProfileRepository,
    private val reach: ReachPolicy,
    private val avatars: AvatarStorage,
) : WarmUp {
    override val name = "users"

    override suspend fun run() {
        val a = UserId.new()
        val b = UserId.new()
        profiles.get(setOf(a, b))
        repository.find(a)
        reach.blockedEitherWay(a, b)
        avatars.urlOf("avatars/$a/warm-up.jpg")
    }
}
