package com.circaso.wallet

import com.circaso.shared.UserId
import com.circaso.wallet.api.MembershipCharge
import com.circaso.wallet.api.SpaceAccess
import com.circaso.wallet.api.SpaceWalletPolicy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Spaces' side of space wallets: who is what in which space, and the membership payments it holds. */
class FakeSpaces : SpaceWalletPolicy {
    val roles = ConcurrentHashMap<Pair<UUID, UserId>, SpaceAccess>()
    val charges = ConcurrentHashMap<UUID, Pair<UserId, MembershipCharge>>()

    override suspend fun access(spaceId: UUID, userId: UserId): SpaceAccess = roles[spaceId to userId] ?: SpaceAccess.NONE

    override suspend fun administered(userId: UserId): List<UUID> =
        roles.filter { (key, access) -> key.second == userId && access == SpaceAccess.ADMIN }.keys.map { it.first }

    override suspend fun team(spaceId: UUID): Set<UserId> =
        roles.filter { (key, access) -> key.first == spaceId && (access == SpaceAccess.OWNER || access == SpaceAccess.ADMIN) }
            .keys
            .map { it.second }
            .toSet()

    override suspend fun membershipCharge(paymentId: UUID, payer: UserId): MembershipCharge? =
        charges[paymentId]?.takeIf { it.first == payer }?.second
}
