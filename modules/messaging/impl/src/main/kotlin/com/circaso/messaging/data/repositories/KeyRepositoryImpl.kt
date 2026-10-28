package com.circaso.messaging.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.messaging.data.tables.KeyBundlesTable
import com.circaso.messaging.data.tables.OneTimePreKeysTable
import com.circaso.messaging.domain.models.KeyBundle
import com.circaso.messaging.domain.models.KyberPreKey
import com.circaso.messaging.domain.models.OneTimePreKey
import com.circaso.messaging.domain.models.SignedPreKey
import com.circaso.messaging.domain.repositories.KeyRepository
import com.circaso.shared.DeviceId
import com.circaso.shared.UserId
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.eqSubQuery
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.batchInsert
import org.jetbrains.exposed.v1.r2dbc.deleteReturning
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.upsert
import java.time.Instant

/** The key directory over `key_bundles` and `one_time_prekeys`. */
class KeyRepositoryImpl(private val database: Database) : KeyRepository {
    override suspend fun bundle(userId: UserId): KeyBundle? =
        database.tx("keys.bundle") {
            KeyBundlesTable
                .selectAll()
                .where { KeyBundlesTable.userId eq userId.value }
                .map { it.toBundle() }
                .singleOrNull()
        }

    override suspend fun lockBundle(userId: UserId): KeyBundle? =
        database.tx("keys.lock-bundle") {
            KeyBundlesTable
                .selectAll()
                .where { KeyBundlesTable.userId eq userId.value }
                .forUpdate(ForUpdateOption.ForUpdate)
                .map { it.toBundle() }
                .singleOrNull()
        }

    override suspend fun oneTimeKeyIds(userId: UserId, deviceId: DeviceId): Set<Int> =
        database.tx("keys.one-time-ids") {
            OneTimePreKeysTable
                .select(OneTimePreKeysTable.keyId)
                .where {
                    (OneTimePreKeysTable.userId eq userId.value) and
                        (OneTimePreKeysTable.deviceId eq deviceId.value)
                }.map { it[OneTimePreKeysTable.keyId] }
                .toList()
                .toSet()
        }

    override suspend fun publish(
        bundle: KeyBundle,
        oneTimePreKeys: List<OneTimePreKey>,
        at: Instant,
    ) {
        database.tx("keys.publish") {
            KeyBundlesTable.upsert(KeyBundlesTable.userId) {
                it[userId] = bundle.userId.value
                it[deviceId] = bundle.deviceId.value
                it[identityKey] = bundle.identityKey
                it[signedPreKeyId] = bundle.signedPreKey.keyId
                it[signedPreKey] = bundle.signedPreKey.publicKey
                it[signedPreKeySignature] = bundle.signedPreKey.signature
                it[signedPreKeyAt] = bundle.signedPreKeyAt
                it[kyberPreKeyId] = bundle.kyberPreKey.keyId
                it[kyberPreKey] = bundle.kyberPreKey.publicKey
                it[kyberPreKeySignature] = bundle.kyberPreKey.signature
                it[uploadedAt] = at
            }
            OneTimePreKeysTable.deleteWhere { userId eq bundle.userId.value }
            insertKeys(bundle.userId, bundle.deviceId, oneTimePreKeys, at)
        }
    }

    override suspend fun addOneTimeKeys(
        userId: UserId,
        deviceId: DeviceId,
        keys: List<OneTimePreKey>,
        at: Instant,
    ) {
        database.tx("keys.add-one-time") { insertKeys(userId, deviceId, keys, at) }
    }

    private suspend fun insertKeys(
        userId: UserId,
        deviceId: DeviceId,
        keys: List<OneTimePreKey>,
        at: Instant,
    ) {
        if (keys.isEmpty()) return
        OneTimePreKeysTable.batchInsert(keys, useMultiRowValues = true, shouldReturnGeneratedValues = false) {
            this[OneTimePreKeysTable.userId] = userId.value
            this[OneTimePreKeysTable.keyId] = it.keyId
            this[OneTimePreKeysTable.deviceId] = deviceId.value
            this[OneTimePreKeysTable.publicKey] = it.publicKey
            this[OneTimePreKeysTable.uploadedAt] = at
        }
    }

    /**
     * `DELETE … WHERE key_id = (the lowest key, FOR UPDATE SKIP LOCKED) RETURNING …`. The pick is a scalar subquery,
     * which PostgreSQL runs once; as `IN (…)` it may run again for each row the delete scans, and with `SKIP LOCKED`
     * each run can pick another key, so one fetch would take several.
     */
    override suspend fun claimOneTimeKey(userId: UserId, deviceId: DeviceId): OneTimePreKey? =
        database.tx("keys.claim-one-time") {
            val mine = (OneTimePreKeysTable.userId eq userId.value) and (OneTimePreKeysTable.deviceId eq deviceId.value)
            val lowest =
                OneTimePreKeysTable
                    .select(OneTimePreKeysTable.keyId)
                    .where { mine }
                    .orderBy(OneTimePreKeysTable.keyId to SortOrder.ASC)
                    .limit(1)
                    .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
            OneTimePreKeysTable
                .deleteReturning { mine and (OneTimePreKeysTable.keyId eqSubQuery lowest) }
                .map { OneTimePreKey(it[OneTimePreKeysTable.keyId], it[OneTimePreKeysTable.publicKey]) }
                .singleOrNull()
        }

    override suspend fun removeBundle(
        userId: UserId,
        deviceId: DeviceId,
        replacedAt: Instant,
    ): Boolean =
        database.tx("keys.remove-bundle") {
            OneTimePreKeysTable.deleteWhere {
                (OneTimePreKeysTable.userId eq userId.value) and
                    (OneTimePreKeysTable.deviceId eq deviceId.value) and
                    (OneTimePreKeysTable.uploadedAt less replacedAt)
            }
            val deleted =
                KeyBundlesTable.deleteWhere {
                    (KeyBundlesTable.userId eq userId.value) and (KeyBundlesTable.deviceId eq deviceId.value)
                }
            deleted > 0
        }
}

private fun ResultRow.toBundle() =
    KeyBundle(
        userId = UserId(this[KeyBundlesTable.userId]),
        deviceId = DeviceId(this[KeyBundlesTable.deviceId]),
        identityKey = this[KeyBundlesTable.identityKey],
        signedPreKey =
            SignedPreKey(
                this[KeyBundlesTable.signedPreKeyId],
                this[KeyBundlesTable.signedPreKey],
                this[KeyBundlesTable.signedPreKeySignature],
            ),
        kyberPreKey =
            KyberPreKey(
                this[KeyBundlesTable.kyberPreKeyId],
                this[KeyBundlesTable.kyberPreKey],
                this[KeyBundlesTable.kyberPreKeySignature],
            ),
        signedPreKeyAt = this[KeyBundlesTable.signedPreKeyAt],
    )
