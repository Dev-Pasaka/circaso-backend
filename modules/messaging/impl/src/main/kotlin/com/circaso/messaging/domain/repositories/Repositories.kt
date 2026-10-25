package com.circaso.messaging.domain.repositories

import com.circaso.messaging.domain.models.KeyBundle
import com.circaso.messaging.domain.models.OneTimePreKey
import com.circaso.shared.UserId

/** The key directory: `key_bundles` and `one_time_prekeys`. */
interface KeyRepository {
    suspend fun bundle(userId: UserId): KeyBundle?

    /** The ids of the one-time prekeys still waiting to be used. At most 100, so reading them all is cheap. */
    suspend fun oneTimeKeyIds(userId: UserId): Set<Int>

    /**
     * Writes [bundle] and [oneTimePreKeys], and deletes the one-time prekeys in [staleKeyIds], in one batch. The
     * caller leaves out of [staleKeyIds] any id it is writing again: a delete and an insert in one batch share a
     * timestamp, and the delete would win.
     */
    suspend fun publish(
        bundle: KeyBundle,
        oneTimePreKeys: List<OneTimePreKey>,
        staleKeyIds: Set<Int>,
    )

    /**
     * Inserts each key only if its id is not stored, so a stored key is never overwritten. True when every key was
     * new; otherwise the keys this call did insert are deleted again and it answers false, so the top-up is refused
     * whole.
     */
    suspend fun addOneTimeKeys(userId: UserId, keys: List<OneTimePreKey>): Boolean

    /**
     * Takes one one-time prekey and deletes it with a lightweight transaction, so two callers never get the same
     * key. Null when none are left, or (rarely) when every key it tried was taken first by someone else.
     */
    suspend fun claimOneTimeKey(userId: UserId): OneTimePreKey?
}
