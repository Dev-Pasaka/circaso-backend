package com.circaso.wallet.presentation.api

import com.circaso.identity.api.DeletionGuard
import com.circaso.integrations.api.CustodyChallenge
import com.circaso.integrations.api.CustodyNotifications
import com.circaso.integrations.api.CustodyTransaction
import com.circaso.messaging.api.ChatId
import com.circaso.shared.CircasoJson
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.RealtimeReplay
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.getOrNull
import com.circaso.wallet.api.GroupWalletSummary
import com.circaso.wallet.api.SpaceWalletSummary
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.api.TransferSummary
import com.circaso.wallet.api.WalletFrames
import com.circaso.wallet.api.Wallets
import com.circaso.wallet.presentation.services.WalletDirectoryService
import java.util.UUID
import com.circaso.shared.Network
import com.circaso.shared.WalletNetwork
import com.circaso.shared.networkFilter
import kotlinx.coroutines.withContext

/** wallet:api for the assistant later. Every call goes through a service, like any other way in. */
class WalletsImpl(private val directory: WalletDirectoryService) : Wallets {
    override suspend fun hasWallet(userId: UserId): Boolean = directory.hasWallet(userId)

    override suspend fun transfer(transferId: TransferId): TransferSummary? = directory.transfer(transferId)

    override suspend fun groupWallet(chatId: ChatId): GroupWalletSummary? = directory.groupWallet(chatId)

    override suspend fun depositAddress(userId: UserId, blockchain: String): String? =
        directory.depositAddress(userId, blockchain)

    override suspend fun spaceWallet(spaceId: UUID): SpaceWalletSummary? = directory.spaceWallet(spaceId)

    override suspend fun spaceWalletHoldsMoney(spaceId: UUID): Resource<Boolean> =
        directory.spaceWalletHoldsMoney(spaceId)
}

/**
 * Identity asks before it schedules a deletion (I14); the wallet refuses while the US dollar stablecoins it holds,
 * added up over every chain, come to 0.01 or more, or while it holds any amount of any other asset (decision
 * 2026-10-28), with its own `wallet.funds_remaining`, which reaches the app as is.
 */
class WalletDeletionGuard(private val directory: WalletDirectoryService) : DeletionGuard {
    /**
     * Both networks are checked (decision 2026-11-18): a person may hold money on either, and the first refusal is
     * the answer. A network they never had a wallet on answers at once, without asking Circle.
     */
    override suspend fun canDelete(userId: UserId): Resource<Unit> {
        for (network in Network.entries) {
            val checked = withContext(WalletNetwork(network)) { directory.canDelete(userId) }
            if (checked is Resource.Error) return checked
        }
        return Resource.success(Unit)
    }
}

/**
 * Integrations' Circle webhook hands every transaction update and every completed wallet-creating challenge here; an
 * error makes Circle deliver it again.
 */
class WalletCustodyNotifications(private val directory: WalletDirectoryService) : CustodyNotifications {
    /**
     * Circle's webhook names the network of the account it came through; an update handed over without one (the dev
     * custody, a test) is on the network its chain belongs to.
     */
    override suspend fun onTransaction(update: CustodyTransaction): Resource<Unit> {
        val network = networkFilter() ?: Network.ofChain(update.blockchain)
        return withContext(WalletNetwork(network)) { directory.onTransaction(update) }
    }

    override suspend fun onWalletChallenge(challenge: CustodyChallenge): Resource<Unit> =
        directory.onWalletChallenge(challenge)
}

/**
 * What the gateway calls after a reconnect with the app's last wallet `seq`: the frames it missed, or a single
 * `resync` frame when the gap is older than the 7 days they are kept, after which the app reloads over HTTP.
 */
class WalletRealtimeReplay(private val directory: WalletDirectoryService) : RealtimeReplay {
    override suspend fun since(userId: UserId, seq: Long): Resource<List<RealtimeEnvelope>> {
        val frames =
            directory.missedFrames(userId, seq).getOrNull()
                ?: return Resource.success(listOf(RealtimeEnvelope.resync(WalletFrames.MODULE, seq)))
        return Resource.success(frames.map { CircasoJson.decodeFromString(RealtimeEnvelope.serializer(), it.json) })
    }
}
