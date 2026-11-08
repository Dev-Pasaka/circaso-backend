package com.circaso.identity.domain.models

import com.circaso.shared.UserId
import java.time.Instant

// What staff read about accounts on the ops gateway (decision 2026-11-08). Presentation masks the phone and emails
// on the way out; the domain keeps them as they are, like every other identity model.

/** An account with its Google and Apple logins. */
data class AccountRecord(val account: Account, val links: List<ProviderLink>)

data class AccountRecordPage(val accounts: List<AccountRecord>, val nextCursor: String?)

/** Where a newest-first page of accounts stopped: the last row's `(created_at, user_id)`. */
data class AccountKey(val createdAt: Instant, val userId: UserId)

/** One of the account's phones; [active] when it is the account's current one. */
data class DeviceRecord(val device: Device, val active: Boolean)

/** A session as it is stored, with the time it runs out (30 days after its last rotation). */
data class SessionRecord(val session: Session, val expiresAt: Instant)
