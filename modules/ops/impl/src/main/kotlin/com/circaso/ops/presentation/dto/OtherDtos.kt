package com.circaso.ops.presentation.dto

import kotlinx.serialization.Serializable

@Serializable
data class DayCountDto(val date: String, val count: Long)

@Serializable
data class UsersTileDto(val total: Long, val newInRange: Long, val series: List<DayCountDto>)

@Serializable
data class ActiveTileDto(val day1: Long, val day7: Long, val day30: Long)

@Serializable
data class MoneyDayDto(val date: String, val count: Long, val volumeUsdMicros: Long)

@Serializable
data class TransactionsTileDto(val count: Long, val volumeUsdMicros: Long, val series: List<MoneyDayDto>)

@Serializable
data class GroupsTileDto(val total: Long, val withWallet: Long, val withoutWallet: Long)

@Serializable
data class FeeDayDto(val date: String, val usdMicros: Long)

@Serializable
data class FeesTileDto(val earnedUsdMicros: Long, val series: List<FeeDayDto>)

@Serializable
data class TicketsTileDto(val open: Long, val pendingUser: Long, val unassigned: Long)

/** A tile the caller's role may not see is null. */
@Serializable
data class DashboardResponse(
    val from: String,
    val to: String,
    val days: Int,
    val users: UsersTileDto? = null,
    val active: ActiveTileDto? = null,
    val transactions: TransactionsTileDto? = null,
    val groups: GroupsTileDto? = null,
    val fees: FeesTileDto? = null,
    val tickets: TicketsTileDto? = null,
)

@Serializable
data class AuditEntryDto(
    val id: String,
    val actorId: String,
    val actorEmail: String,
    val actorRole: String,
    val action: String,
    val targetType: String,
    val targetId: String? = null,
    val reason: String? = null,
    val detail: String? = null,
    val outcome: String,
    val errorCode: String? = null,
    val at: String,
    val requestId: String? = null,
)

@Serializable
data class AuditPageResponse(val items: List<AuditEntryDto>, val nextCursor: String? = null)

@Serializable
data class SupportAccountDto(
    val userId: String,
    val displayName: String,
    val handle: String? = null,
    val createdAt: String,
)

@Serializable
data class SupportAccountsResponse(val accounts: List<SupportAccountDto>, val nextCursor: String? = null)

@Serializable
data class CreateSupportAccountRequest(val displayName: String = "", val handle: String = "")
