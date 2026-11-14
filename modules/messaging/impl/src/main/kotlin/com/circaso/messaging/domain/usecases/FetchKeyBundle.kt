package com.circaso.messaging.domain.usecases

import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.FetchedBundle
import com.circaso.messaging.domain.ports.BundleRateLimit
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.KeyRepository
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.api.ReachPolicy

/**
 * M3 `GET /keys/{userId}`: the person's bundle, using up one of their one-time prekeys. 60 fetches per caller per
 * hour, so nobody can drain someone's prekeys. The caller must reach the person, share a group with them with
 * no block either way, or have a direct chat with someone who still reaches them (group members need 1:1 sessions to hand each other sender keys). That is checked before the
 * bundle is looked up, so someone who qualifies neither way can't learn whether they have keys.
 */
class FetchKeyBundle(
    private val keys: KeyRepository,
    private val reach: ReachPolicy,
    private val chats: ChatRepository,
    private val rateLimit: BundleRateLimit,
    private val config: MessagingConfig,
) {
    suspend operator fun invoke(caller: UserId, target: UserId): Resource<FetchedBundle> {
        if (rateLimit.count(caller) > config.bundlesPerHour) {
            val wait = rateLimit.retryAfter(caller)?.seconds?.coerceAtLeast(1) ?: SECONDS_PER_HOUR
            return Resource.error(MessagingError.RateLimited(wait))
        }
        if (!reach.canReach(caller, target) && !sharesChatUnblocked(caller, target)) {
            return Resource.error(MessagingError.NotReachable)
        }

        val bundle = keys.bundle(target) ?: return Resource.error(MessagingError.KeysNotFound)
        val oneTimePreKey = keys.claimOneTimeKey(target, bundle.deviceId)
        return Resource.success(
            FetchedBundle(target, bundle.identityKey, bundle.signedPreKey, bundle.kyberPreKey, oneTimePreKey),
        )
    }

    /**
     * A shared group (decision 2026-10-26) or an existing direct chat that the other person still reaches me in
     * (2026-11-14, see SendMessage) stands in for reach, but never overrides a block.
     */
    private suspend fun sharesChatUnblocked(caller: UserId, target: UserId) =
        (chats.shareGroup(caller, target) && !reach.blockedEitherWay(caller, target)) ||
            (reach.canReach(target, caller) && chats.directChat(caller, target, null) != null)

    private companion object {
        const val SECONDS_PER_HOUR = 3_600L
    }
}
