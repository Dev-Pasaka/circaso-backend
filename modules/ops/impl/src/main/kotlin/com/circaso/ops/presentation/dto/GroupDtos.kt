package com.circaso.ops.presentation.dto

import kotlinx.serialization.Serializable

@Serializable
data class GroupRowDto(
    val chatId: String,
    val memberCount: Int,
    val ownerId: String? = null,
    val ownerName: String? = null,
    val createdAt: String,
    val hasWallet: Boolean,
)

@Serializable
data class GroupPageResponse(val items: List<GroupRowDto>, val nextCursor: String? = null)

@Serializable
data class GroupListResponse(val items: List<GroupRowDto>)

@Serializable
data class GroupMemberDto(val userId: String, val displayName: String? = null, val role: String)

/** [balances] and [totalUsdMicros] are empty and null for a role that may not see money. */
@Serializable
data class GroupWalletDto(
    val signerId: String,
    val createdAt: String,
    val balances: List<BalanceDto>,
    val totalUsdMicros: Long? = null,
    val balancesAvailable: Boolean,
)

@Serializable
data class GroupDetailResponse(
    val group: GroupRowDto,
    val members: List<GroupMemberDto>,
    val wallet: GroupWalletDto? = null,
)
