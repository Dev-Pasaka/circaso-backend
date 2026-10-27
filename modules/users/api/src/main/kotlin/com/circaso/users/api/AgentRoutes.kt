package com.circaso.users.api

import kotlinx.serialization.Serializable

// What users' read-only agent routes (`/v1/agent/users/…`) answer. They live here, not in users' presentation,
// because the assistant reads them over HTTP with the agent token (decision 2026-10-27, AI1b review).

/** What the assistant gets: a smaller shape than the app's, with only what it needs to resolve "Mum" to a person. */
@Serializable
data class AgentContactDto(val userId: String, val displayName: String, val handle: String? = null)

@Serializable
data class AgentContactsResponse(val contacts: List<AgentContactDto>, val nextCursor: String? = null)
