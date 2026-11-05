package com.circaso.wallet.domain.usecases

import com.circaso.identity.api.AccountDirectory
import com.circaso.identity.api.AccountStatus
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.api.SpaceAccess
import com.circaso.wallet.api.SpaceWalletPolicy
import com.circaso.wallet.api.SpaceWalletSummary
import com.circaso.wallet.api.WalletId
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.CreateSpaceWalletCommand
import com.circaso.wallet.domain.models.SpaceWalletSetup
import com.circaso.wallet.domain.models.SpaceWalletView
import com.circaso.wallet.domain.models.WalletOwner
import com.circaso.wallet.domain.repositories.WalletOwnerRepository
import com.circaso.wallet.domain.support.HeldFunds
import com.circaso.wallet.domain.support.SupportAccountRule
import com.circaso.wallet.domain.support.WalletCreation
import com.circaso.wallet.domain.support.WalletListings
import java.time.Clock
import java.util.UUID

/**
 * W18 `POST /wallet/spaces/{spaceId}` (Spaces phase 3, SN4): the space's owner sets up the space's own wallet, its own
 * Circle user with its own PIN, filed under a fresh [WalletId]; the owner becomes its signer and sends from it
 * directly (no approvals, no daily cap for spaces, plan §7a). One wallet per space, claimed by
 * `wallet_owners_space_key` before Circle is asked. Asking again while `SETTING_UP` gives a fresh challenge (Finish
 * Wallet Setup, SN5b); once `ACTIVE`, `wallet.already_exists`. The space goes live when the wallet turns active
 * (`WalletCreated` with `spaceId`).
 */
class CreateSpaceWallet(
    private val owners: WalletOwnerRepository,
    private val accounts: AccountDirectory,
    private val support: SupportAccountRule,
    private val creation: WalletCreation,
    private val listings: WalletListings,
    private val spaces: SpaceWalletPolicy,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: CreateSpaceWalletCommand): Resource<SpaceWalletSetup> {
        support.refusal(command.userId)?.let { return it }
        when (spaces.access(command.spaceId, command.userId)) {
            SpaceAccess.OWNER -> Unit
            SpaceAccess.NONE -> return Resource.error(WalletError.SpaceNotFound)
            else -> return Resource.error(WalletError.NotSpaceOwner)
        }
        if (accounts.status(command.userId) != AccountStatus.ACTIVE) return Resource.error(WalletError.AccountRestricted)
        val owner = owners.bySpace(command.spaceId) ?: claim(command) ?: return Resource.error(WalletError.SpaceNotFound)
        if (owner.signerId != command.userId) return Resource.error(WalletError.NotSigner)
        if (listings.of(owner, command.userId).status.name == "ACTIVE") return Resource.error(WalletError.AlreadyExists)
        return when (val setup = creation.start(owner.holder, command.idempotencyKey)) {
            is Resource.Success -> Resource.success(SpaceWalletSetup(owner, setup.data))
            is Resource.Error -> setup
        }
    }

    private suspend fun claim(command: CreateSpaceWalletCommand): WalletOwner.Space? {
        val fresh =
            WalletOwner.Space(
                walletId = WalletId.new(),
                spaceId = command.spaceId,
                signerId = command.userId,
                createdBy = command.userId,
                createdAt = clock.instant(),
            )
        if (owners.insertSpace(fresh)) return fresh
        return owners.bySpace(command.spaceId)
    }
}

/** W19 `GET /wallet/spaces/{spaceId}`: the space's wallet for its owner and admins: none, being set up, or active. */
class GetSpaceWallet(
    private val owners: WalletOwnerRepository,
    private val listings: WalletListings,
    private val spaces: SpaceWalletPolicy,
) {
    suspend operator fun invoke(userId: UserId, spaceId: UUID): Resource<SpaceWalletView> {
        val access = spaces.access(spaceId, userId)
        if (access != SpaceAccess.OWNER && access != SpaceAccess.ADMIN) return Resource.error(WalletError.SpaceNotFound)
        val owner = owners.bySpace(spaceId) ?: return Resource.success(SpaceWalletView(spaceId, listing = null))
        return Resource.success(SpaceWalletView(spaceId, listings.of(owner, userId)))
    }
}

/** `Wallets.spaceWallet`, for spaces: the space's wallet, its status, signer and the chains it can be paid on. */
class FindSpaceWallet(private val owners: WalletOwnerRepository, private val listings: WalletListings) {
    suspend operator fun invoke(spaceId: UUID): SpaceWalletSummary? {
        val owner = owners.bySpace(spaceId) ?: return null
        val listing = listings.of(owner, caller = null)
        return SpaceWalletSummary(owner.walletId, spaceId, listing.status.name, owner.signerId, listing.blockchains)
    }
}

/** `Wallets.spaceWalletHoldsMoney`, for spaces: a space is deleted only once its wallet is empty (read from Circle). */
class CheckSpaceWalletFunds(private val owners: WalletOwnerRepository, private val funds: HeldFunds) {
    suspend operator fun invoke(spaceId: UUID): Resource<Boolean> {
        val owner = owners.bySpace(spaceId) ?: return Resource.success(false)
        return funds.holdsMoney(owner.holder)
    }
}
