package com.circaso.ops.domain.models

import com.circaso.identity.api.AccountKind
import com.circaso.identity.api.AccountStatus
import com.circaso.identity.api.ActiveCounts
import com.circaso.identity.api.AdminAccount
import com.circaso.identity.api.AdminDevice
import com.circaso.identity.api.AdminSession
import com.circaso.identity.api.SignUpStats
import com.circaso.messaging.api.OpsGroup
import com.circaso.shared.UserId
import com.circaso.support.api.TicketCounts
import com.circaso.support.api.TicketDetail
import com.circaso.support.api.TicketSummary
import com.circaso.users.api.AdminProfile
import com.circaso.wallet.api.FinanceReport
import com.circaso.wallet.api.GroupWalletRef
import com.circaso.wallet.api.OpsHoldings
import com.circaso.wallet.api.OpsTransfer
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// What the staff use cases answer: the owning modules' own contract types, put together. Names come from users'
// profiles; staff emails from the staff list. Nothing here is stored by ops.

/** One search result: the account and, when it has one, its profile. */
data class UserSummary(
    val userId: UserId,
    val displayName: String?,
    val handle: String?,
    val status: AccountStatus,
    val kind: AccountKind,
    val maskedPhone: String?,
    val createdAt: Instant,
)

data class UserPage(val items: List<UserSummary>, val nextCursor: String?)

/** The latest suspension's audit line, while the account is suspended: since when, why and by whom. */
data class Suspension(val since: Instant, val reason: String?, val byEmail: String)

data class UserAccount(val account: AdminAccount, val profile: AdminProfile?, val suspension: Suspension?)

data class UserDevices(val devices: List<AdminDevice>, val sessions: List<AdminSession>)

/** A transfer with the names of both sides, when they have a profile. */
data class TransferView(val transfer: OpsTransfer, val senderName: String?, val recipientName: String?)

data class TransferViewPage(val items: List<TransferView>, val nextCursor: String?)

/** A user's transfers page and, on the first page only, the fees they paid in all. */
data class UserTransfers(val page: TransferViewPage, val feesPaidUsdMicros: Long)

data class GroupSummary(val group: OpsGroup, val ownerName: String?, val hasWallet: Boolean)

data class GroupSummaryPage(val items: List<GroupSummary>, val nextCursor: String?)

/** Which groups a list shows. */
enum class WalletFilter { ANY, WITH, WITHOUT }

data class GroupMemberView(val userId: UserId, val displayName: String?, val role: String)

/** [holdings] only for a role that may see money ([Permission.GROUPS_MONEY]), else null. */
data class GroupDetail(
    val row: GroupSummary,
    val members: List<GroupMemberView>,
    val wallet: GroupWalletRef?,
    val holdings: OpsHoldings?,
)

/** A ticket line with the user's name and the assignee's email. */
data class TicketLine(val summary: TicketSummary, val userName: String?, val assigneeEmail: String?)

data class TicketLinePage(val items: List<TicketLine>, val nextCursor: String?)

/** A whole ticket for staff, with the email of every staff member who wrote in it. */
data class TicketView(val detail: TicketDetail, val row: TicketLine, val staffEmails: Map<UUID, String>)

data class GroupTotals(val total: Long, val withWallet: Long)

/** Each tile is null when the caller's role may not see it (decision 2026-11-08: each role sees its tiles). */
data class Dashboard(
    val from: LocalDate,
    val to: LocalDate,
    val days: Int,
    val users: SignUpStats?,
    val active: ActiveCounts?,
    val money: FinanceReport?,
    val groups: GroupTotals?,
    val tickets: TicketCounts?,
)

/** A support account (OPS1, OPS2) as the portal lists it. */
data class SupportAccountEntry(
    val userId: UserId,
    val displayName: String,
    val handle: String?,
    val createdAt: Instant,
)

data class SupportAccountList(val accounts: List<SupportAccountEntry>, val nextCursor: String?)

/** The finance page: the wallet's report and how many group wallets there are. */
data class Finance(val report: FinanceReport, val groupWallets: Long)
