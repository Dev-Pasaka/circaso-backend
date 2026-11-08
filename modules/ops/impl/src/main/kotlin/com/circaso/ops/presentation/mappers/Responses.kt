package com.circaso.ops.presentation.mappers

import com.circaso.identity.api.AdminDevice
import com.circaso.identity.api.AdminSession
import com.circaso.ops.domain.models.AuditEntry
import com.circaso.ops.domain.models.AuditPage
import com.circaso.ops.domain.models.Dashboard
import com.circaso.ops.domain.models.GroupDetail
import com.circaso.ops.domain.models.GroupSummary
import com.circaso.ops.domain.models.Staff
import com.circaso.ops.domain.models.SupportAccountEntry
import com.circaso.ops.domain.models.TicketLine
import com.circaso.ops.domain.models.TicketView
import com.circaso.ops.domain.models.TransferView
import com.circaso.ops.domain.models.UserAccount
import com.circaso.ops.domain.models.UserDevices
import com.circaso.ops.domain.models.UserSummary
import com.circaso.ops.presentation.dto.ActiveTileDto
import com.circaso.ops.presentation.dto.AuditEntryDto
import com.circaso.ops.presentation.dto.AuditPageResponse
import com.circaso.ops.presentation.dto.BalanceDto
import com.circaso.ops.presentation.dto.ChainAssetDto
import com.circaso.ops.presentation.dto.ChainWalletDto
import com.circaso.ops.presentation.dto.DashboardResponse
import com.circaso.ops.presentation.dto.DayCountDto
import com.circaso.ops.presentation.dto.DeviceDto
import com.circaso.ops.presentation.dto.FeeDayDto
import com.circaso.ops.presentation.dto.FeesTileDto
import com.circaso.ops.presentation.dto.FinanceResponse
import com.circaso.ops.presentation.dto.FinanceTotalsDto
import com.circaso.ops.presentation.dto.GroupDetailResponse
import com.circaso.ops.presentation.dto.GroupMemberDto
import com.circaso.ops.presentation.dto.GroupSummaryDto
import com.circaso.ops.presentation.dto.GroupWalletDto
import com.circaso.ops.presentation.dto.GroupWalletsDto
import com.circaso.ops.presentation.dto.GroupsTileDto
import com.circaso.ops.presentation.dto.HoldingsResponse
import com.circaso.ops.presentation.dto.MoneyDayDto
import com.circaso.ops.presentation.dto.PeriodDto
import com.circaso.ops.presentation.dto.SessionDto
import com.circaso.ops.presentation.dto.SignInDto
import com.circaso.ops.presentation.dto.StaffDto
import com.circaso.ops.presentation.dto.SupportAccountDto
import com.circaso.ops.presentation.dto.SuspensionDto
import com.circaso.ops.presentation.dto.TicketContextDto
import com.circaso.ops.presentation.dto.TicketDetailResponse
import com.circaso.ops.presentation.dto.TicketLineDto
import com.circaso.ops.presentation.dto.TicketMessageDto
import com.circaso.ops.presentation.dto.TicketsTileDto
import com.circaso.ops.presentation.dto.TransactionsTileDto
import com.circaso.ops.presentation.dto.TransferDto
import com.circaso.ops.presentation.dto.UserAccountResponse
import com.circaso.ops.presentation.dto.UserDevicesResponse
import com.circaso.ops.presentation.dto.UserSummaryDto
import com.circaso.ops.presentation.dto.UsersTileDto
import com.circaso.wallet.api.FinanceReport
import com.circaso.wallet.api.OpsBalance
import com.circaso.wallet.api.OpsHoldings
import java.time.Instant

fun Staff.toDto() =
    StaffDto(
        id = id.toString(),
        email = email,
        role = role.name,
        active = active,
        fromConfig = fromConfig,
        addedBy = addedBy,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )

fun UserSummary.toDto() =
    UserSummaryDto(
        userId = userId.toString(),
        displayName = displayName,
        handle = handle,
        status = status.name,
        kind = kind.name,
        maskedPhone = maskedPhone,
        createdAt = createdAt.toString(),
    )

fun UserAccount.toResponse() =
    UserAccountResponse(
        userId = account.userId.toString(),
        displayName = profile?.displayName,
        handle = profile?.handle,
        about = profile?.about,
        avatarUrl = profile?.avatarUrl,
        status = account.status.name,
        kind = account.kind.name,
        verification = account.verification,
        maskedPhone = account.maskedPhone,
        createdAt = account.createdAt.toString(),
        signIns = account.signIns.map { SignInDto(it.provider, it.maskedEmail, it.linkedAt.toString()) },
        suspension = suspension?.let { SuspensionDto(it.since.toString(), it.reason, it.byEmail) },
    )

fun UserDevices.toResponse() =
    UserDevicesResponse(
        devices = devices.map { it.toDto() },
        sessions = sessions.map { it.toDto() },
    )

private fun AdminDevice.toDto() =
    DeviceDto(
        deviceId = deviceId.toString(),
        platform = platform,
        model = model,
        appVersion = appVersion,
        status = status,
        registeredAt = registeredAt.toString(),
        lastSeenAt = lastSeenAt.toString(),
        active = active,
    )

private fun AdminSession.toDto() =
    SessionDto(
        sessionId = sessionId.toString(),
        deviceId = deviceId.toString(),
        scope = scope,
        status = status,
        createdAt = createdAt.toString(),
        rotatedAt = rotatedAt.toString(),
        expiresAt = expiresAt.toString(),
    )

fun OpsBalance.toDto() = BalanceDto(asset, amountMinor.toString(), decimals, usdMicros)

fun OpsHoldings.toResponse() =
    HoldingsResponse(
        hasWallet = hasWallet,
        wallets =
            wallets.map { wallet ->
                ChainWalletDto(wallet.blockchain, wallet.address, wallet.state, wallet.balances.map { it.toDto() })
            },
        totalUsdMicros = totalUsdMicros,
        balancesAvailable = balancesAvailable,
    )

fun TransferView.toDto(): TransferDto {
    val t = transfer
    return TransferDto(
        transferId = t.transferId.toString(),
        senderId = t.senderId.toString(),
        senderName = senderName,
        recipientId = t.recipientId?.toString(),
        recipientName = recipientName,
        destinationAddress = t.destinationAddress,
        blockchain = t.blockchain,
        asset = t.asset,
        amountMinor = t.amountMinor.toString(),
        decimals = t.decimals,
        feeMinor = t.feeMinor.toString(),
        usdTotalMicros = t.usdTotalMicros,
        state = t.state,
        origin = t.origin,
        kind = t.kind.name,
        txHash = t.txHash,
        failureReason = t.failureReason,
        createdAt = t.createdAt.toString(),
        updatedAt = t.updatedAt.toString(),
    )
}

fun FinanceReport.toResponse(
    from: Instant,
    to: Instant,
    bucket: String,
    groupWallets: Long,
) = FinanceResponse(
    from = from.toString(),
    to = to.toString(),
    bucket = bucket,
    series = series.map { PeriodDto(it.period.toString(), it.count, it.volumeUsdMicros, it.feesUsdMicros) },
    byChainAsset =
        byChainAsset.map {
            ChainAssetDto(
                blockchain = it.blockchain,
                asset = it.asset,
                count = it.count,
                amountMinor = it.amountMinor.toString(),
                decimals = it.decimals,
                volumeUsdMicros = it.volumeUsdMicros,
                feesUsdMicros = it.feesUsdMicros,
            )
        },
    totals = FinanceTotalsDto(count, volumeUsdMicros, feesUsdMicros),
    groupWallets = GroupWalletsDto(groupWallets),
)

fun GroupSummary.toDto() =
    GroupSummaryDto(
        chatId = group.chatId.value.toString(),
        memberCount = group.memberCount,
        ownerId = group.ownerId?.toString(),
        ownerName = ownerName,
        createdAt = group.createdAt.toString(),
        hasWallet = hasWallet,
    )

fun GroupDetail.toResponse() =
    GroupDetailResponse(
        group = row.toDto(),
        members = members.map { GroupMemberDto(it.userId.toString(), it.displayName, it.role) },
        wallet =
            wallet?.let { ref ->
                GroupWalletDto(
                    signerId = ref.signerId.toString(),
                    createdAt = ref.createdAt.toString(),
                    balances = holdings?.wallets?.flatMap { chain -> chain.balances.map { it.toDto() } }.orEmpty(),
                    totalUsdMicros = holdings?.totalUsdMicros,
                    balancesAvailable = holdings?.balancesAvailable ?: false,
                )
            },
    )

fun TicketLine.toDto(): TicketLineDto {
    val s = summary
    return TicketLineDto(
        ticketId = s.ticketId.toString(),
        number = s.number,
        userId = s.userId.toString(),
        userName = userName,
        subject = s.subject,
        category = s.category.name,
        status = s.status.name,
        source = s.source.name,
        assigneeId = s.assigneeId?.toString(),
        assigneeEmail = assigneeEmail,
        createdAt = s.createdAt.toString(),
        updatedAt = s.updatedAt.toString(),
        lastMessageAt = s.lastMessageAt.toString(),
        waitingOn = s.waitingOn?.name,
    )
}

fun TicketView.toResponse() =
    TicketDetailResponse(
        ticket = row.toDto(),
        description = detail.description,
        context =
            TicketContextDto(
                transferId = detail.context.transferId?.toString(),
                chatId = detail.context.chatId?.toString(),
            ),
        messages =
            detail.messages.map {
                TicketMessageDto(
                    messageId = it.messageId.toString(),
                    author = it.author.name,
                    staffEmail = it.staffId?.let(staffEmails::get),
                    body = it.body,
                    createdAt = it.createdAt.toString(),
                    internal = it.internal,
                )
            },
    )

fun Dashboard.toResponse(): DashboardResponse {
    val money = money
    return DashboardResponse(
        from = from.toString(),
        to = to.toString(),
        days = days,
        users =
            users?.let { stats ->
                UsersTileDto(stats.total, stats.inRange, stats.series.map { DayCountDto(it.date.toString(), it.count) })
            },
        active = active?.let { ActiveTileDto(it.day1, it.day7, it.day30) },
        transactions =
            money?.let { report ->
                TransactionsTileDto(
                    count = report.count,
                    volumeUsdMicros = report.volumeUsdMicros,
                    series = report.series.map { MoneyDayDto(it.period.toString(), it.count, it.volumeUsdMicros) },
                )
            },
        groups = groups?.let { GroupsTileDto(it.total, it.withWallet, (it.total - it.withWallet).coerceAtLeast(0)) },
        fees =
            money?.let { report ->
                FeesTileDto(
                    report.feesUsdMicros,
                    report.series.map { FeeDayDto(it.period.toString(), it.feesUsdMicros) },
                )
            },
        tickets = tickets?.let { TicketsTileDto(it.open, it.pendingUser, it.unassigned) },
    )
}

fun AuditEntry.toDto() =
    AuditEntryDto(
        id = id.toString(),
        actorId = actorId.toString(),
        actorEmail = actorEmail,
        actorRole = actorRole.name,
        action = action,
        targetType = targetType,
        targetId = targetId,
        reason = reason,
        detail = detail,
        outcome = outcome.name,
        errorCode = errorCode,
        at = at.toString(),
        requestId = requestId,
    )

fun AuditPage.toResponse() = AuditPageResponse(items.map { it.toDto() }, nextCursor)

fun SupportAccountEntry.toDto() = SupportAccountDto(userId.toString(), displayName, handle, createdAt.toString())
