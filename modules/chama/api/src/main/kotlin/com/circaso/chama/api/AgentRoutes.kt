package com.circaso.chama.api

import kotlinx.serialization.Serializable

// What chama's read-only agent routes (`/v1/agent/chama/…`) answer, read by the assistant over HTTP with the agent
// token, as it reads users' (decision 2026-10-27; chama's routes decision 2026-10-31). Amounts are USDC in whole
// units ("1250.5"), exact; times are ISO-8601 UTC.

/** A person the chama mentions: their id and, when they still have a profile, their name. */
@Serializable
data class AgentChamaPersonDto(val userId: String, val displayName: String? = null)

/** A request to pay someone from the group that is still open (`PENDING` or `APPROVED`). */
@Serializable
data class AgentChamaRequestDto(
    val requestId: String,
    val state: String,
    val amount: String,
    val requestedBy: AgentChamaPersonDto,
    val payee: AgentChamaPersonDto? = null,
    val payeeName: String? = null,
    val note: String? = null,
    val approvals: Int,
    val approvalsNeeded: Int,
    val expiresAt: String,
)

@Serializable
data class AgentContributorDto(val person: AgentChamaPersonDto, val amount: String)

/** One ledger line: `IN` a member's contribution, `OUT` a request paid out. */
@Serializable
data class AgentLedgerLineDto(
    val kind: String,
    val amount: String,
    val at: String,
    val person: AgentChamaPersonDto? = null,
    val note: String? = null,
)

/**
 * A chama as the asking member sees it. `progressPercent` is contributed over target, rounded down. `contributors`
 * and `recent` (the latest 10 ledger lines) are filled for one chama, empty in the list.
 */
@Serializable
data class AgentChamaDto(
    val chatId: String,
    val name: String,
    val asset: String,
    val target: String,
    val contributed: String,
    val paidOut: String,
    val held: String,
    val myContribution: String,
    val progressPercent: Int,
    val deadline: String? = null,
    val treasurer: AgentChamaPersonDto,
    val memberCount: Int,
    val isAdmin: Boolean,
    val isTreasurer: Boolean,
    val openRequests: List<AgentChamaRequestDto>,
    val contributors: List<AgentContributorDto> = emptyList(),
    val recent: List<AgentLedgerLineDto> = emptyList(),
)

@Serializable
data class AgentChamasResponse(val chamas: List<AgentChamaDto>)

@Serializable
data class AgentChamaResponse(val chama: AgentChamaDto)
