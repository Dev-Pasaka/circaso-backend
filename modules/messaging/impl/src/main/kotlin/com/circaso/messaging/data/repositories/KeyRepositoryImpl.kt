package com.circaso.messaging.data.repositories

import com.circaso.messaging.data.entities.KeyBundleRow
import com.circaso.messaging.data.entities.OneTimePreKeyRow
import com.circaso.messaging.data.mappers.toModel
import com.circaso.messaging.data.mappers.toRow
import com.circaso.messaging.domain.models.KeyBundle
import com.circaso.messaging.domain.models.OneTimePreKey
import com.circaso.messaging.domain.repositories.KeyRepository
import com.circaso.shared.UserId
import com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException
import io.kandra.core.ExperimentalKandraApi
import io.kandra.core.exception.KandraQueryException
import io.kandra.runtime.KandraRuntime
import io.kandra.runtime.dsl.KandraColumnRef
import io.kandra.runtime.dsl.KandraRawQuery
import io.kandra.runtime.repository.KandraSuspendRepository
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

// Column name Kandra derives from the entity property (camelCase to snake_case).
private val USER_ID = KandraColumnRef<UUID>("user_id")

/**
 * Kandra has no conditional delete, so the claim is written as CQL. `IF EXISTS` makes it a lightweight
 * transaction: of two callers deleting the same row, only one sees `[applied] = true`.
 */
private const val CLAIM_CQL =
    "DELETE FROM circaso_messaging.one_time_prekeys WHERE user_id = ? AND key_id = ? IF EXISTS"

/**
 * Rolls back a top-up's own row: a lightweight transaction like the insert (a plain delete mixed with it could lose
 * to clock skew and leave the row), and only if the row still holds the public key this request wrote.
 */
private const val ROLL_BACK_CQL =
    "DELETE FROM circaso_messaging.one_time_prekeys WHERE user_id = ? AND key_id = ? IF public_key = ?"

private val log = LoggerFactory.getLogger("com.circaso.messaging.data")

/**
 * The key directory on Kandra. [insertIfAbsent] is the `IF NOT EXISTS` insert of one one-time prekey; tests replace
 * it to fail part-way through a top-up.
 */
@OptIn(ExperimentalKandraApi::class)
class KeyRepositoryImpl(
    private val runtime: KandraRuntime,
    private val bundles: KandraSuspendRepository<KeyBundleRow>,
    private val prekeys: KandraSuspendRepository<OneTimePreKeyRow>,
    private val insertIfAbsent: suspend (OneTimePreKeyRow) -> Boolean = { prekeys.saveIfNotExists(it) },
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

    /**
     * One `IF NOT EXISTS` insert per key, in key-id order, stopping at the first id already stored. With the same
     * order everywhere, of several identical top-ups racing only the one that wins the lowest id gets further, so
     * exactly one succeeds.
     *
     * A failed call rolls back every row it attempted, the one in flight included: an insert that timed out may have
     * applied, and Kandra retries `IF NOT EXISTS`, so a retry can answer "exists" for a row this call wrote. The
     * rollback deletes only rows still holding this call's public key. The one case it can't tell apart is a racing
     * top-up that sent the very same key for the same id: that twin row may go too, which loses a key but never
     * serves a wrong one.
     */
    override suspend fun addOneTimeKeys(userId: UserId, keys: List<OneTimePreKey>): Boolean {
        val attempted = mutableListOf<OneTimePreKeyRow>()
        try {
            for (key in keys.sortedBy { it.keyId }) {
                val row = key.toRow(userId)
                attempted += row
                if (!insertIfAbsent(row)) {
                    val failed = rollBack(attempted)
                    if (failed.isNotEmpty()) log.warn("prekey rollback failed: user={} keyIds={}", userId, failed)
                    return false
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            for (keyId in rollBack(attempted)) {
                e.addSuppressed(RollbackFailed(keyId))
            }
            throw e
        }
        return true
    }

    /** Conditionally deletes each row; returns the key ids whose delete failed. */
    private suspend fun rollBack(rows: List<OneTimePreKeyRow>): List<Int> {
        val failed = mutableListOf<Int>()
        for (row in rows) {
            val query =
                KandraRawQuery
                    .cql(ROLL_BACK_CQL)
                    .bind(row.userId, row.keyId, row.publicKey)
                    .build()
            try {
                prekeys.rawQuery(query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += row.keyId
            }
        }
        return failed
    }

    /** Names only the key id, never key material; added as suppressed so the original exception stays the error. */
    private class RollbackFailed(keyId: Int) : RuntimeException("prekey rollback failed for keyId $keyId")

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

    /**
     * A lightweight transaction that times out under contention may or may not have applied. It counts as lost
     * either way: at worst that key is deleted without being handed out, never handed out twice.
     */
    private suspend fun delete(row: OneTimePreKeyRow): Boolean {
        val query =
            KandraRawQuery
                .cql(CLAIM_CQL)
                .bind(row.userId, row.keyId)
                .build()
        return try {
            prekeys.rawQuery(query).firstOrNull()?.getBoolean("[applied]") == true
        } catch (e: WriteTimeoutException) {
            false
        } catch (e: KandraQueryException) {
            if (e.cause !is WriteTimeoutException) throw e
            false
        }
    }

    private companion object {
        const val CLAIM_ROUNDS = 5
        const val CANDIDATES = 10
    }
}
