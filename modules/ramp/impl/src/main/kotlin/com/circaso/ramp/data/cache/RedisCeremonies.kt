package com.circaso.ramp.data.cache

import com.circaso.infrastructure.api.RedisScope
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.models.StepUpAction
import com.circaso.ramp.domain.ports.CeremonyStore
import com.circaso.ramp.domain.ports.KeptChallenge
import com.circaso.ramp.domain.ports.KeptRegistration
import com.circaso.shared.CircasoJson
import kotlinx.serialization.Serializable
import java.time.Duration
import java.util.Base64
import java.util.UUID

@Serializable
private data class StoredRegistration(val merchantId: String, val label: String, val state: String)

@Serializable
private data class StoredChallenge(
    val merchantId: String? = null,
    val action: String? = null,
    val bindingHash: String,
    val nonce: String,
)

/**
 * WebAuthn ceremony state under `rmp:wa:` (2026-11-24): `reg:{id}` (a registration's options, its merchant and label)
 * and `ch:{id}` (a step-up's or sign-in's nonce and binding). Each expires on its own and is taken with GETDEL, so it
 * answers once: a replayed assertion finds nothing.
 */
class RedisCeremonies(private val redis: RedisScope) : CeremonyStore {
    override suspend fun keepRegistration(
        id: String,
        kept: KeptRegistration,
        ttl: Duration,
    ) {
        val value = StoredRegistration(kept.merchantId.toString(), kept.label, kept.state)
        redis.set("wa:reg:$id", CircasoJson.encodeToString(StoredRegistration.serializer(), value), ttl)
    }

    override suspend fun takeRegistration(id: String): KeptRegistration? {
        val raw = redis.getAndDelete("wa:reg:$id") ?: return null
        val stored = runCatching { CircasoJson.decodeFromString(StoredRegistration.serializer(), raw) }.getOrNull()
        return stored?.let { KeptRegistration(MerchantId(UUID.fromString(it.merchantId)), it.label, it.state) }
    }

    override suspend fun keepChallenge(
        id: String,
        kept: KeptChallenge,
        ttl: Duration,
    ) {
        val value =
            StoredChallenge(
                merchantId = kept.merchantId?.toString(),
                action = kept.action?.name,
                bindingHash = kept.bindingHash,
                nonce = Base64.getEncoder().encodeToString(kept.nonce),
            )
        redis.set("wa:ch:$id", CircasoJson.encodeToString(StoredChallenge.serializer(), value), ttl)
    }

    override suspend fun takeChallenge(id: String): KeptChallenge? {
        val raw = redis.getAndDelete("wa:ch:$id") ?: return null
        val stored = runCatching { CircasoJson.decodeFromString(StoredChallenge.serializer(), raw) }.getOrNull()
        return stored?.let {
            KeptChallenge(
                merchantId = it.merchantId?.let { id -> MerchantId(UUID.fromString(id)) },
                action = it.action?.let(StepUpAction::valueOf),
                bindingHash = it.bindingHash,
                nonce = Base64.getDecoder().decode(it.nonce),
            )
        }
    }
}
