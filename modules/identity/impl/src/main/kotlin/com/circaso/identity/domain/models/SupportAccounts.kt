package com.circaso.identity.domain.models

/**
 * Ops asks for a support account (decision 2026-10-31). [createdBy] is the staff member's id on the ops gateway
 * (decision 2026-11-08: staff sign in there with Cloudflare Access, not with a Circaso account), kept as text because it
 * is the ops module's id, not a user id.
 */
data class CreateSupportAccountCommand(val createdBy: String, val displayName: String, val handle: String)

/**
 * A support account with the profile users holds for it. [displayName] and [handle] are null when users has no
 * profile for it yet (its event is still on its way) or lost the handle to someone faster.
 */
data class SupportAccount(val account: Account, val displayName: String?, val handle: String?)

data class SupportAccountPage(val accounts: List<SupportAccount>, val nextCursor: String?)
