package com.circaso.wallet.domain.support

import com.circaso.integrations.api.StablecoinCustody
import com.circaso.shared.ErrorKind
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.ports.WalletWarnings
import com.circaso.wallet.domain.repositories.WalletRepository
import java.time.Instant

/**
 * Compares a user's transactions at Circle with ours (Wallet → Data → Scheduled jobs, `ReconcileWallets`): each one
 * Circle lists since a point in time goes through [CustodyUpdates], exactly as its webhook would have, so a lost
 * `transactions.inbound` inserts the recipient's `IN` row and sends `payment.received` (and its push), and a lost
 * `transactions.outbound` moves the transfer, its `OUT` row and the chat's payment card. What we already have
 * changes nothing: the unique keys and the transition guard settle it.
 *
 * Users are taken one at a time, at most the caller's batch; the first time Circle can't be reached the pass stops
 * and backs off ([Backoff]), and the rest wait for a later run.
 */
class Reconciler(
    private val wallets: WalletRepository,
    private val custody: StablecoinCustody,
    private val updates: CustodyUpdates,
    private val warnings: WalletWarnings,
) {
    /** The number of Circle transactions looked at. */
    suspend fun reconcile(
        job: String,
        users: List<UserId>,
        since: Instant,
        perUser: Int,
        backoff: Backoff,
    ): Int {
        if (users.isEmpty() || backoff.skip()) return 0
        var seen = 0
        for (userId in users) {
            val user = wallets.circleUser(userId) ?: continue
            val listed =
                when (val read = custody.transactions(user.circleUserId.toString(), since, perUser)) {
                    is Resource.Success -> {
                        read.data
                    }

                    is Resource.Error -> {
                        if (read.error.kind == ErrorKind.UPSTREAM) {
                            warnings.jobBackingOff(job, backoff.failed())
                            return seen
                        }
                        continue
                    }
                }
            // Oldest first, so the history fills in the order it happened.
            for (transaction in listed.sortedBy { it.updatedAt }) {
                if (updates.apply(transaction) is Resource.Error) {
                    warnings.jobBackingOff(job, backoff.failed())
                    return seen
                }
                seen++
            }
        }
        backoff.succeeded()
        return seen
    }
}
