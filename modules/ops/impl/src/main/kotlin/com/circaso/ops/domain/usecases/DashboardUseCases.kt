package com.circaso.ops.domain.usecases

import com.circaso.identity.api.AccountAdmin
import com.circaso.messaging.api.GroupReports
import com.circaso.ops.domain.models.Dashboard
import com.circaso.ops.domain.models.GroupTotals
import com.circaso.ops.domain.models.Permission
import com.circaso.ops.domain.support.OpsSettings
import com.circaso.ops.domain.support.Ranges
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.StaffRole
import com.circaso.shared.getOrNull
import com.circaso.support.api.SupportDesk
import com.circaso.wallet.api.ReportBucket
import com.circaso.wallet.api.WalletReports
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Clock

/**
 * The dashboard (decision 2026-11-08): users and new users over time, active users, transactions (count and volume),
 * groups with and without wallets, fees earned and open tickets. Each role gets only its tiles: a tile it may not see
 * is never even read. The tiles are read at the same time; each is one bounded, index-served query in its module.
 */
class GetDashboard(
    private val accounts: AccountAdmin,
    private val wallets: WalletReports,
    private val groups: GroupReports,
    private val desk: SupportDesk,
    private val settings: OpsSettings,
    private val clock: Clock,
) {
    suspend operator fun invoke(role: StaffRole, days: Int): Resource<Dashboard> {
        if (days !in 1..settings.maxRangeDays) {
            return Resource.error(SharedError.Malformed("days", "must be 1 to ${settings.maxRangeDays}"))
        }
        val now = clock.instant()
        val to = Ranges.today(now)
        val from = to.minusDays(days.toLong() - 1)
        val seesUsers = Permission.DASHBOARD_USERS.allows(role)
        val seesMoney = Permission.DASHBOARD_MONEY.allows(role)
        val seesTickets = Permission.DASHBOARD_TICKETS.allows(role)
        return coroutineScope {
            val users = async { if (seesUsers) accounts.signUps(from, to) else null }
            val active = async { if (seesUsers) accounts.activeUsers(now) else null }
            val money =
                async {
                    if (seesMoney) wallets.report(Ranges.startOf(from), Ranges.endOf(to), ReportBucket.DAY) else null
                }
            val groupTotals =
                async {
                    if (seesUsers ||
                        seesMoney
                    ) {
                        GroupTotals(groups.groupCount(), wallets.groupWalletCount())
                    } else {
                        null
                    }
                }
            val tickets = async { if (seesTickets) desk.counts().getOrNull() else null }
            Resource.success(
                Dashboard(
                    from = from,
                    to = to,
                    days = days,
                    users = users.await(),
                    active = active.await(),
                    money = money.await(),
                    groups = groupTotals.await(),
                    tickets = tickets.await(),
                ),
            )
        }
    }
}
