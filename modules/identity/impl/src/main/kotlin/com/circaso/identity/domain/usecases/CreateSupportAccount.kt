package com.circaso.identity.domain.usecases

import com.circaso.identity.api.AccountKind
import com.circaso.identity.api.AccountStatus
import com.circaso.identity.api.SupportAccountCreated
import com.circaso.identity.api.SupportProfiles
import com.circaso.identity.domain.models.Account
import com.circaso.identity.domain.models.CreateSupportAccountCommand
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.models.SupportAccount
import com.circaso.identity.domain.models.Verification
import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.envelope
import java.time.Clock

/**
 * OPS1 `POST /ops/support-accounts`: a Circaso support account (decision 2026-10-31). It is `ACTIVE` at once, with
 * no phone, device or Google/Apple login, so no sign-in path can ever find it, and each of them refuses it anyway.
 * Users is asked first whether the handle may be taken (reserved names such as `circaso` are allowed only here), then
 * makes the profile and claims the handle from `identity.SupportAccountCreated`.
 *
 * The handle is checked, not held: two creates racing for one handle both pass, users gives it to the first event it
 * handles and leaves the other profile without one. Only ops calls this, so the list shows it and ops can retry.
 */
class CreateSupportAccount(
    private val transactor: Transactor,
    private val accounts: AccountRepository,
    private val store: IdentityStore,
    private val profiles: SupportProfiles,
    private val bus: EventBus,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: CreateSupportAccountCommand): Resource<SupportAccount> {
        val handle =
            when (val checked = profiles.checkHandle(command.handle)) {
                is Resource.Success -> checked.data
                is Resource.Error -> return checked
            }

        val account =
            Account(
                userId = UserId.new(),
                phone = null,
                status = AccountStatus.ACTIVE,
                activeDeviceId = null,
                verification = Verification.NONE,
                sessionVersion = 1,
                createdAt = clock.instant(),
                kind = AccountKind.SUPPORT,
            )
        val created = SupportAccountCreated(account.userId, command.displayName, handle)
        val changes =
            Changes(
                securityEvents = listOf(audit(account, command)),
                events = listOf(bus.envelope(created)),
            )
        transactor.run("accounts.create-support") {
            accounts.create(account)
            store.write(changes)
        }
        store.publish(changes)
        return Resource.success(SupportAccount(account, command.displayName, handle))
    }

    /** Who made it, in the support account's own security log. */
    private fun audit(account: Account, command: CreateSupportAccountCommand) =
        SecurityEvent(
            account.userId,
            null,
            SecurityEventType.SUPPORT_ACCOUNT_CREATED,
            null,
            "by ${command.operatorId}",
        )
}
