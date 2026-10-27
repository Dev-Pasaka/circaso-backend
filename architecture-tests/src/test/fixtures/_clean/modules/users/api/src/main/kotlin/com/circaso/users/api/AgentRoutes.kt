package com.circaso.users.api

// An HTTP response another module consumes belongs to the contract (decision A, 2026-10-27).
data class AgentContactsResponse(val contacts: List<String>, val nextCursor: String? = null)
