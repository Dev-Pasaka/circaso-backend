package com.circaso.identity.domain.models

import com.circaso.shared.UserId

/** Ops asks for a support account (decision 2026-10-31); [operatorId] is whoever holds the `ops` token. */
data class CreateSupportAccountCommand(val operatorId: UserId, val displayName: String, val handle: String)

/**
 * A support account with the profile users holds for it. [displayName] and [handle] are null when users has no
 * profile for it yet (its event is still on its way) or lost the handle to someone faster.
 */
data class SupportAccount(val account: Account, val displayName: String?, val handle: String?)

data class SupportAccountPage(val accounts: List<SupportAccount>, val nextCursor: String?)
