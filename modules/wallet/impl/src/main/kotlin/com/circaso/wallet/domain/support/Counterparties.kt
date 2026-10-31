package com.circaso.wallet.domain.support

import com.circaso.shared.UserId
import com.circaso.users.api.Profiles
import com.circaso.wallet.domain.models.Counterparty
import com.circaso.wallet.domain.models.HistoryEntry
import com.circaso.wallet.domain.models.WalletTransaction
import com.circaso.wallet.domain.repositories.WalletRepository

/**
 * Names the other side of history rows from users' `Profiles`, asked once for every person in the batch. Someone
 * whose profile is gone (a deleted account) keeps their id and address but no name. A row that only has an address
 * (a payment made outside the app's send flow, or recorded before its sender was known) is matched to the Circaso
 * wallet at that address, so a payment between two Circaso people always names the other person.
 */
class Counterparties(private val profiles: Profiles, private val wallets: WalletRepository? = null) {
    suspend fun entries(rows: List<WalletTransaction>): List<HistoryEntry> {
        val owners = owners(rows)
        val people = rows.mapNotNullTo(mutableSetOf()) { it.counterpartyId ?: owners[it.chainAddress()] }
        val named = names(people)
        return rows.map {
            val who = it.counterpartyId ?: owners[it.chainAddress()]
            HistoryEntry(it, counterparty(who, it.counterpartyAddress, named))
        }
    }

    /** The Circaso owner of each address that a row names without a person, one lookup per address and chain. */
    private suspend fun owners(rows: List<WalletTransaction>): Map<Pair<String, String>, UserId> {
        val repository = wallets ?: return emptyMap()
        return rows
            .filter { it.counterpartyId == null }
            .mapNotNullTo(mutableSetOf()) { it.chainAddress() }
            .mapNotNull { key -> repository.walletAt(key.first, key.second)?.let { key to it.userId } }
            .filter { (_, owner) -> rows.none { it.userId == owner } } // our own other wallet isn't "someone"
            .toMap()
    }

    private fun WalletTransaction.chainAddress(): Pair<String, String>? = counterpartyAddress?.let { it to blockchain }

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
