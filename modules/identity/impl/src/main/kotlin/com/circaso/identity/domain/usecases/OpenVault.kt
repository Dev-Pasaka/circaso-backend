package com.circaso.identity.domain.usecases

import com.circaso.identity.api.PinVaultOpened
import com.circaso.identity.api.Principal
import com.circaso.identity.domain.errors.VaultError
import com.circaso.identity.domain.models.PinVault
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.ports.IdentityCache
import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.DeviceRepository
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.identity.domain.repositories.ProviderRepository
import com.circaso.identity.domain.repositories.VaultRepository
import com.circaso.integrations.api.Email
import com.circaso.integrations.api.EmailGateway
import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.shared.envelope

/**
 * V2 `GET /vault`: hands the phone the PIN Vault's blob to decrypt, which is an **opening** (2026-11-11). Each one
 * logs `VAULT_OPENED` with the phone, writes `identity.PinVaultOpened` to the outbox in the same transaction (the
 * wallet pauses outgoing payments for 24 hours on it), and emails the owner. At most [MAX_OPENINGS] an hour per
 * account (`request.rate_limited`): a phone opens it once to restore the PIN, so more is someone trying their luck.
 *
 * No push: the opening phone is the account's one active phone, so a push would only reach whoever opened it. The
 * email goes to the Google or Apple address, which an attacker holding the phone doesn't control.
 */
class OpenVault(
    private val transactor: Transactor,
    private val accounts: AccountRepository,
    private val devices: DeviceRepository,
    private val providers: ProviderRepository,
    private val vaults: VaultRepository,
    private val store: IdentityStore,
    private val cache: IdentityCache,
    private val email: EmailGateway,
    private val bus: EventBus,
) {
    suspend operator fun invoke(principal: Principal): Resource<PinVault> {
        val userId = principal.userId
        if (cache.countVaultOpening(userId) > MAX_OPENINGS) return Resource.error(rateLimited(userId))

        val opened = Changes(events = listOf(bus.envelope(PinVaultOpened(userId))))
        val (vault, model) =
            transactor.run("vaults.open") {
                accounts.lock(userId) ?: return@run null
                val vault = vaults.find(userId) ?: return@run null
                val model = devices.find(userId, principal.deviceId)?.model
                val logged =
                    SecurityEvent(userId, null, SecurityEventType.VAULT_OPENED, model, vault.version.toString())
                store.write(opened.copy(securityEvents = listOf(logged)))
                vault to model
            } ?: return Resource.error(VaultError.NotFound)
        store.publish(opened)
        tellOwner(
            userId,
            model,
            opened.events
                .single()
                .eventId
                .toString(),
        )
        return Resource.success(vault)
    }

    private suspend fun rateLimited(userId: UserId): SharedError {
        val wait = cache.vaultOpeningRetryAfter(userId)?.let { (it.toMillis() + 999) / 1000 }?.coerceAtLeast(1)
        return SharedError.RateLimited(wait ?: WINDOW_SECONDS)
    }

    /** Best effort, once per opening (keyed by the event): a failed email never keeps the PIN from its owner. */
    private suspend fun tellOwner(
        userId: UserId,
        model: String?,
        openingId: String,
    ) {
        val to = providers.links(userId).firstNotNullOfOrNull { it.email } ?: return
        email.send(to, Email.PinVaultOpened(model ?: UNKNOWN_PHONE), idempotencyKey = "vault-opened-$openingId")
    }

    private companion object {
        const val MAX_OPENINGS = 5L
        const val WINDOW_SECONDS = 3600L
        const val UNKNOWN_PHONE = "your phone"
    }
}
