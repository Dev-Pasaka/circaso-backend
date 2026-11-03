package com.circaso.chama.domain.support

import com.circaso.chama.domain.models.ChamaView
import com.circaso.chama.domain.models.LedgerEntry
import com.circaso.shared.UserId
import com.circaso.users.api.Profiles

/** Display names of the people a chama mentions, asked of users once per answer. */
class ChamaNames(private val profiles: Profiles) {
    suspend fun of(people: Set<UserId>): Map<UserId, String> =
        if (people.isEmpty()) emptyMap() else profiles.get(people).mapValues { it.value.displayName }

    companion object {
        fun people(
            view: ChamaView,
            recent: List<LedgerEntry>,
            withContributors: Boolean,
        ): Set<UserId> =
            buildSet {
                (view.wallet?.signerId ?: view.goal.treasurerId)?.let(::add)
                if (withContributors) addAll(view.contributorsMinor.keys)
                view.openRequests.forEach { request ->
                    add(request.requestedBy)
                    request.payeeId?.let(::add)
                }
                recent.forEach { entry ->
                    when (entry) {
                        is LedgerEntry.In -> add(entry.contribution.userId)
                        is LedgerEntry.Out -> entry.request.payeeId?.let(::add)
                    }
                }
            }
    }
}
