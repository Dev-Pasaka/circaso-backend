package com.circaso.wallet.domain.support

import com.circaso.shared.UserId
import com.circaso.users.api.Profiles
import com.circaso.wallet.domain.models.Counterparty
import com.circaso.wallet.domain.models.HistoryEntry
import com.circaso.wallet.domain.models.WalletTransaction

/**
 * Names the other side of history rows from users' `Profiles`, asked once for every person in the batch. Someone
 * whose profile is gone (a deleted account) keeps their id and address but no name.
 */
class Counterparties(private val profiles: Profiles) {
    suspend fun entries(rows: List<WalletTransaction>): List<HistoryEntry> {
        val named = names(rows.mapNotNullTo(mutableSetOf()) { it.counterpartyId })
        return rows.map { HistoryEntry(it, counterparty(it.counterpartyId, it.counterpartyAddress, named)) }
    }

    suspend fun names(people: Set<UserId>): Map<UserId, Counterparty> =
        if (people.isEmpty()) {
            emptyMap()
        } else {
            profiles.get(people).mapValues { (id, profile) ->
                Counterparty(id, profile.displayName, profile.handle, profile.avatarUrl, null)
            }
        }

    fun counterparty(
        userId: UserId?,
        address: String?,
        named: Map<UserId, Counterparty>,
    ): Counterparty {
        val person = userId?.let { named[it] }
        return Counterparty(userId, person?.displayName, person?.handle, person?.avatarUrl, address)
    }
}
