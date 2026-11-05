package com.circaso.spaces.presentation.services

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.spaces.api.SpaceId
import com.circaso.spaces.domain.ports.LoggedFrame
import com.circaso.spaces.domain.usecases.ConfirmMembershipPayment
import com.circaso.spaces.domain.usecases.GoLiveWhenWalletReady
import com.circaso.spaces.domain.usecases.PurgeSpaceMedia
import com.circaso.spaces.domain.usecases.RemoveAccountFromSpaces
import com.circaso.spaces.domain.usecases.ReplayFrames
import com.circaso.spaces.domain.usecases.RunMembershipTimers
import java.util.UUID

/** Other modules' events: the wallet's (payments, wallets ready) and identity's (deleted accounts). */
class EventsService(
    private val confirmMembershipPayment: ConfirmMembershipPayment,
    private val goLiveWhenWalletReady: GoLiveWhenWalletReady,
    private val removeAccountFromSpaces: RemoveAccountFromSpaces,
) {
    suspend fun paymentConfirmed(
        paymentId: UUID,
        payer: UserId,
        amountMinor: Long,
    ): Resource<Unit> = confirmMembershipPayment(paymentId, payer, amountMinor)

    suspend fun walletReady(spaceId: SpaceId): Resource<Unit> = goLiveWhenWalletReady(spaceId)

    suspend fun accountDeleted(userId: UserId): Resource<Unit> = removeAccountFromSpaces(userId)
}

/** Spaces' scheduled jobs. */
class JobsService(private val runMembershipTimers: RunMembershipTimers, private val purgeSpaceMedia: PurgeSpaceMedia) {
    suspend fun memberships(): Resource<Int> = runMembershipTimers()

    suspend fun purgeMedia(): Resource<Int> = purgeSpaceMedia()
}

/** The gateway's replay after a reconnect. */
class RealtimeService(private val replayFrames: ReplayFrames) {
    suspend fun missedFrames(userId: UserId, seq: Long): Resource<List<LoggedFrame>?> = replayFrames(userId, seq)
}
