package com.circaso.ops.domain.usecases

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.GroupReports
import com.circaso.messaging.api.OpsGroup
import com.circaso.ops.domain.errors.OpsError
import com.circaso.ops.domain.models.GroupDetail
import com.circaso.ops.domain.models.GroupMemberView
import com.circaso.ops.domain.models.GroupRow
import com.circaso.ops.domain.models.GroupRowPage
import com.circaso.ops.domain.models.WalletFilter
import com.circaso.ops.domain.support.UserViews
import com.circaso.shared.Resource
import com.circaso.shared.flatMap
import com.circaso.shared.getOrNull
import com.circaso.wallet.api.WalletReports

// Groups, for every role. A group's name and photo are end-to-end encrypted, so staff see ids, sizes, owners and
// whether the group has a wallet; balances only for a role that may see money.

private suspend fun rowsOf(
    found: List<OpsGroup>,
    wallets: WalletReports,
    views: UserViews,
): List<GroupRow> {
    val withWallet = wallets.groupWalletsOf(found.map { it.chatId }.toSet())
    val owners = views.names(found.mapNotNull { it.ownerId }.toSet())
    return found.map { GroupRow(it, it.ownerId?.let(owners::get), it.chatId in withWallet) }
}

/**
 * Groups, newest first. With a wallet: paged over the wallets (each group has at most one). Without: paged over the
 * groups, the ones with a wallet left out, so a page can come back shorter than asked while more follow.
 */
class ListGroups(private val groups: GroupReports, private val wallets: WalletReports, private val views: UserViews) {
    suspend operator fun invoke(
        filter: WalletFilter,
        cursor: String?,
        limit: Int,
    ): Resource<GroupRowPage> =
        when (filter) {
            WalletFilter.WITH -> {
                wallets.groupWallets(cursor, limit).flatMap { page ->
                    val found = groups.groupsByIds(page.items.map { it.chatId }.toSet())
                    val ordered = page.items.mapNotNull { found[it.chatId] }
                    Resource.success(GroupRowPage(rowsOf(ordered, wallets, views), page.nextCursor))
                }
            }

            WalletFilter.ANY, WalletFilter.WITHOUT -> {
                groups.groups(cursor, limit).flatMap { page ->
                    val rows = rowsOf(page.items, wallets, views)
                    val shown = if (filter == WalletFilter.WITHOUT) rows.filterNot { it.hasWallet } else rows
                    Resource.success(GroupRowPage(shown, page.nextCursor))
                }
            }
        }
}

/** A group with its members and, when it has one, its wallet; [withMoney] adds the wallet's balances. */
class GetGroup(private val groups: GroupReports, private val wallets: WalletReports, private val views: UserViews) {
    suspend operator fun invoke(chatId: ChatId, withMoney: Boolean): Resource<GroupDetail> {
        val group = groups.groupsByIds(setOf(chatId))[chatId] ?: return Resource.error(OpsError.GroupNotFound)
        val members = groups.members(chatId, MAX_MEMBERS)
        val names = views.names(members.map { it.userId }.toSet() + listOfNotNull(group.ownerId))
        val wallet = wallets.groupWalletsOf(setOf(chatId))[chatId]
        val holdings = if (withMoney && wallet != null) wallets.holdings(wallet.walletId).getOrNull() else null
        val row = GroupRow(group, group.ownerId?.let(names::get), wallet != null)
        return Resource.success(
            GroupDetail(
                row = row,
                members = members.map { GroupMemberView(it.userId, names[it.userId], it.role) },
                wallet = wallet,
                holdings = holdings,
            ),
        )
    }

    private companion object {
        const val MAX_MEMBERS = 1_024
    }
}
