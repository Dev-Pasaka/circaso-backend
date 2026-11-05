package com.circaso.spaces.presentation.services

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.spaces.api.SpaceId
import com.circaso.spaces.domain.usecases.CheckPrivateChat
import com.circaso.spaces.domain.usecases.FindMembershipCharge
import com.circaso.spaces.domain.usecases.GetSpaceName
import com.circaso.spaces.domain.usecases.GetSpaceTeam
import com.circaso.spaces.domain.usecases.GetWalletAccess
import com.circaso.spaces.domain.usecases.ListAdministeredSpaces
import com.circaso.wallet.api.MembershipCharge
import com.circaso.wallet.api.SpaceAccess
import java.util.UUID

/** What the wallet and messaging ask spaces through the guards spaces implements. */
class PolicyService(
    private val getWalletAccess: GetWalletAccess,
    private val listAdministeredSpaces: ListAdministeredSpaces,
    private val getSpaceTeam: GetSpaceTeam,
    private val findMembershipCharge: FindMembershipCharge,
    private val checkPrivateChat: CheckPrivateChat,
    private val getSpaceName: GetSpaceName,
) {
    suspend fun name(spaceId: SpaceId): Resource<String?> = getSpaceName(spaceId)

    suspend fun access(spaceId: SpaceId, userId: UserId): Resource<SpaceAccess> = getWalletAccess(spaceId, userId)

    suspend fun administered(userId: UserId): Resource<List<SpaceId>> = listAdministeredSpaces(userId)

    suspend fun team(spaceId: SpaceId): Resource<Set<UserId>> = getSpaceTeam(spaceId)

    suspend fun charge(paymentId: UUID, payer: UserId): Resource<MembershipCharge?> =
        findMembershipCharge(paymentId, payer)

    suspend fun privateChat(
        spaceId: SpaceId,
        from: UserId,
        to: UserId,
    ): Resource<Boolean> = checkPrivateChat(spaceId, from, to)
}
