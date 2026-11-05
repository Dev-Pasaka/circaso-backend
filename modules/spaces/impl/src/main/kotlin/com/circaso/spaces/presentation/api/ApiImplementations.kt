package com.circaso.spaces.presentation.api

import com.circaso.messaging.api.SpacePrivateChats
import com.circaso.shared.CircasoJson
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.RealtimeReplay
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.getOrNull
import com.circaso.spaces.api.SpaceId
import com.circaso.spaces.api.SpacesFrames
import com.circaso.spaces.presentation.services.PolicyService
import com.circaso.spaces.presentation.services.RealtimeService
import com.circaso.wallet.api.MembershipCharge
import com.circaso.wallet.api.SpaceAccess
import com.circaso.wallet.api.SpaceWalletPolicy
import java.util.UUID

/**
 * The wallet's guard for space wallets (wallet:api `SpaceWalletPolicy`): who may see, sign for and pay a space's
 * wallet, and what a membership costs. Fails closed: when spaces can't answer, nobody gets access.
 */
class SpaceWalletPolicyImpl(private val policy: PolicyService) : SpaceWalletPolicy {
    override suspend fun access(spaceId: UUID, userId: UserId): SpaceAccess =
        policy.access(SpaceId(spaceId), userId).getOrNull() ?: SpaceAccess.NONE

    override suspend fun administered(userId: UserId): List<UUID> =
        policy
            .administered(userId)
            .getOrNull()
            .orEmpty()
            .map { it.value }

    override suspend fun team(spaceId: UUID): Set<UserId> = policy.team(SpaceId(spaceId)).getOrNull().orEmpty()

    override suspend fun membershipCharge(paymentId: UUID, payer: UserId): MembershipCharge? =
        policy.charge(paymentId, payer).getOrNull()
}

/** Messaging's guard for private chats with a space's owner (messaging:api `SpacePrivateChats`). Fails closed. */
class SpacePrivateChatsImpl(private val policy: PolicyService) : SpacePrivateChats {
    override suspend fun allowed(
        spaceId: UUID,
        from: UserId,
        to: UserId,
    ): Boolean = policy.privateChat(SpaceId(spaceId), from, to).getOrNull() == true
}

/** What the gateway calls after a reconnect with the app's last `spaces` seq: the kept frames, or `resync`. */
class SpacesRealtimeReplay(private val realtime: RealtimeService) : RealtimeReplay {
    override suspend fun since(userId: UserId, seq: Long): Resource<List<RealtimeEnvelope>> {
        val frames =
            realtime.missedFrames(userId, seq).getOrNull()
                ?: return Resource.success(listOf(RealtimeEnvelope.resync(SpacesFrames.MODULE, seq)))
        return Resource.success(frames.map { CircasoJson.decodeFromString(RealtimeEnvelope.serializer(), it.json) })
    }
}
