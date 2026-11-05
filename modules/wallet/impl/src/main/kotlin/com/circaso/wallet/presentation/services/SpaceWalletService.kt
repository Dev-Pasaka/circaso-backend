package com.circaso.wallet.presentation.services

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.models.CreateSpaceWalletCommand
import com.circaso.wallet.domain.models.SpaceWalletSetup
import com.circaso.wallet.domain.models.SpaceWalletView
import com.circaso.wallet.domain.usecases.CreateSpaceWallet
import com.circaso.wallet.domain.usecases.GetSpaceWallet
import java.util.UUID

/** Space wallets (Spaces phase 3): W18, W19. */
class SpaceWalletService(private val createSpaceWallet: CreateSpaceWallet, private val getSpaceWallet: GetSpaceWallet) {
    suspend fun create(command: CreateSpaceWalletCommand): Resource<SpaceWalletSetup> = createSpaceWallet(command)

    suspend fun get(userId: UserId, spaceId: UUID): Resource<SpaceWalletView> = getSpaceWallet(userId, spaceId)
}
