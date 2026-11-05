package com.circaso.wallet.presentation.mappers

import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import com.circaso.wallet.domain.models.CreateSpaceWalletCommand
import com.circaso.wallet.domain.models.SpaceWalletSetup
import com.circaso.wallet.domain.models.SpaceWalletView
import com.circaso.wallet.domain.models.WalletStatus
import com.circaso.wallet.presentation.dto.SpaceWalletResponse
import com.circaso.wallet.presentation.dto.SpaceWalletSetupResponse
import java.util.UUID

fun spaceIdOf(raw: String?): Resource<UUID> =
    raw?.let { Uuid7.parse(it) }?.let { Resource.success(it) }
        ?: Resource.error(SharedError.Malformed("spaceId", "is not an id"))

fun createSpaceWalletCommand(
    userId: UserId,
    spaceId: UUID,
    requestKey: String,
): CreateSpaceWalletCommand =
    CreateSpaceWalletCommand(userId, spaceId, circleKey(userId, "wallet.create-space:$spaceId", requestKey))

fun SpaceWalletSetup.toResponse() =
    SpaceWalletSetupResponse(
        walletId = owner.walletId.toString(),
        spaceId = owner.spaceId.toString(),
        status = setup.status.name,
        challengeId = setup.challengeId,
        sdk = setup.sdk.toDto(),
    )

fun SpaceWalletView.toResponse() =
    SpaceWalletResponse(spaceId.toString(), listing?.status?.name ?: WalletStatus.NONE.name, listing?.toDto())
