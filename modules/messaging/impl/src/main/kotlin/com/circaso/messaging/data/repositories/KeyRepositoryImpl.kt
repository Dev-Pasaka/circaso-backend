package com.circaso.messaging.data.repositories

import com.circaso.messaging.data.entities.KeyBundleRow
import com.circaso.messaging.data.entities.OneTimePreKeyRow
import com.circaso.messaging.data.mappers.toModel
import com.circaso.messaging.data.mappers.toRow
import com.circaso.messaging.domain.models.KeyBundle
import com.circaso.messaging.domain.models.OneTimePreKey
import com.circaso.messaging.domain.repositories.KeyRepository
import com.circaso.shared.UserId
import io.kandra.core.ExperimentalKandraApi
import io.kandra.runtime.KandraRuntime
import io.kandra.runtime.dsl.KandraColumnRef
import io.kandra.runtime.dsl.KandraRawQuery
import io.kandra.runtime.repository.KandraSuspendRepository
import java.util.UUID

// Column name Kandra derives from the entity property (camelCase to snake_case).
private val USER_ID = KandraColumnRef<UUID>("user_id")

/**
 * Kandra has no conditional delete, so the claim is written as CQL. `IF EXISTS` makes it a lightweight
 * transaction: of two callers deleting the same row, only one sees `[applied] = true`.
 */
private const val CLAIM_CQL =
    "DELETE FROM circaso_messaging.one_time_prekeys WHERE user_id = ? AND key_id = ? IF EXISTS"

@OptIn(ExperimentalKandraApi::class)
class KeyRepositoryImpl(
    private val runtime: KandraRuntime,
    private val bundles: KandraSuspendRepository<KeyBundleRow>,
    private val prekeys: KandraSuspendRepository<OneTimePreKeyRow>,
) : KeyRepository {
    override suspend fun bundle(userId: UserId): KeyBundle? = bundles.findById(userId.value)?.toModel()

    override suspend fun oneTimeKeyIds(userId: UserId): Set<Int> =
        prekeys.findAll { USER_ID eq userId.value }.map { it.keyId }.toSet()

    override suspend fun publish(
        bundle: KeyBundle,
        oneTimePreKeys: List<OneTimePreKey>,
        staleKeyIds: Set<Int>,
    ) {
        runtime.batch {
            bundles.saveInBatch(bundle.toRow())
            for (key in oneTimePreKeys) {
                prekeys.saveInBatch(key.toRow(bundle.userId))
            }
            for (keyId in staleKeyIds) {
                prekeys.deleteInBatch(OneTimePreKeyRow(bundle.userId.value, keyId, ""))
            }
        }
    }

    override suspend fun addOneTimeKeys(userId: UserId, keys: List<OneTimePreKey>) {
        prekeys.saveAll(keys.map { it.toRow(userId) })
    }

    /**
     * Reads the first few keys and tries to delete them in order; the first delete that applies is ours. A caller
     * that loses every race reads again, a few rounds at most, then answers without a key, which X3DH allows.
     */
    override suspend fun claimOneTimeKey(userId: UserId): OneTimePreKey? {
        repeat(CLAIM_ROUNDS) {
            val candidates = prekeys.findAll(limit = CANDIDATES) { USER_ID eq userId.value }
            if (candidates.isEmpty()) return null
            val claimed = candidates.firstOrNull { delete(it) }
            if (claimed != null) return claimed.toModel()
        }
        return null
    }

    private suspend fun delete(row: OneTimePreKeyRow): Boolean {
        val query =
            KandraRawQuery
                .cql(CLAIM_CQL)
                .bind(row.userId, row.keyId)
                .build()
        return prekeys.rawQuery(query).firstOrNull()?.getBoolean("[applied]") == true
    }

    private companion object {
        const val CLAIM_ROUNDS = 5
        const val CANDIDATES = 10
    }
}
