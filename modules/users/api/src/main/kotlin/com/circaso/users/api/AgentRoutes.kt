package com.circaso.users.api

import kotlinx.serialization.Serializable

// What users' read-only agent routes (`/v1/agent/users/…`) answer. They live here, not in users' presentation,
// because the assistant reads them over HTTP with the agent token (decision 2026-10-27, AI1b review).

/** What the assistant gets: a smaller shape than the app's, with only what it needs to resolve "Mum" to a person. */
@Serializable
data class AgentContactDto(val userId: String, val displayName: String, val handle: String? = null)

@Serializable
data class AgentContactsResponse(val contacts: List<AgentContactDto>, val nextCursor: String? = null)

/**
 * The user's own profile for the assistant (`/v1/agent/users/me`, decision 2026-10-31): what the user would see on
 * their profile screen, minus settings. Never a phone number.
 */
@Serializable
data class AgentProfileResponse(
    val userId: String,
    val displayName: String,
    val handle: String? = null,
    val about: String? = null,
    val avatarUrl: String? = null,
    val support: Boolean = false,
    /** ISO-8601 UTC, when the profile was made. */
    val memberSince: String,
)

/** The people the user has blocked (`/v1/agent/users/blocks`), at most 50. */
@Serializable
data class AgentBlocksResponse(val blocked: List<AgentContactDto>, val nextCursor: String? = null)
