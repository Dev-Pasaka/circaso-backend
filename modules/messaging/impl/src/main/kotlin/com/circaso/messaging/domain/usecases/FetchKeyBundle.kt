package com.circaso.messaging.domain.usecases

import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.FetchedBundle
import com.circaso.messaging.domain.ports.BundleRateLimit
import com.circaso.messaging.domain.repositories.KeyRepository
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.api.ReachPolicy

/**
 * M3 `GET /keys/{userId}`: the person's bundle, using up one of their one-time prekeys. 60 fetches per caller per
 * hour, so nobody can drain someone's prekeys. Reach is checked before the bundle is looked up, so someone who
 * can't reach a person can't learn whether they have keys.
 */
class FetchKeyBundle(
    private val keys: KeyRepository,
    private val reach: ReachPolicy,
    private val rateLimit: BundleRateLimit,
    private val config: MessagingConfig,
) {
    suspend operator fun invoke(caller: UserId, target: UserId): Resource<FetchedBundle> {
        if (rateLimit.count(caller) > config.bundlesPerHour) {
            val wait = rateLimit.retryAfter(caller)?.seconds?.coerceAtLeast(1) ?: SECONDS_PER_HOUR
            return Resource.error(MessagingError.RateLimited(wait))
        }
        // TODO(MS3): a shared group is the other way to qualify (Messaging → Endpoints, M3), once chats exist.
        if (!reach.canReach(caller, target)) return Resource.error(MessagingError.NotReachable)

        val bundle = keys.bundle(target) ?: return Resource.error(MessagingError.KeysNotFound)
        val oneTimePreKey = keys.claimOneTimeKey(target)
        return Resource.success(FetchedBundle(target, bundle.identityKey, bundle.signedPreKey, oneTimePreKey))
    }

    private companion object {
        const val SECONDS_PER_HOUR = 3_600L
    }
}
