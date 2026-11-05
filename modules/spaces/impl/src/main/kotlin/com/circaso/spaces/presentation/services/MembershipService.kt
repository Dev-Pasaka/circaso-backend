package com.circaso.spaces.presentation.services

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.spaces.api.SpaceId
import com.circaso.spaces.domain.models.MeView
import com.circaso.spaces.domain.models.NotificationLevel
import com.circaso.spaces.domain.models.PaymentView
import com.circaso.spaces.domain.usecases.FollowSpace
import com.circaso.spaces.domain.usecases.LeaveSpace
import com.circaso.spaces.domain.usecases.SetNotifications
import com.circaso.spaces.domain.usecases.StartMembershipPayment

/** Joining and leaving: SP10-SP13. */
class MembershipService(
    private val followSpace: FollowSpace,
    private val leaveSpace: LeaveSpace,
    private val setNotifications: SetNotifications,
    private val startMembershipPayment: StartMembershipPayment,
) {
    suspend fun follow(caller: UserId, spaceId: SpaceId): Resource<MeView> = followSpace(caller, spaceId)

    suspend fun leave(caller: UserId, spaceId: SpaceId): Resource<Unit> = leaveSpace(caller, spaceId)

    suspend fun notifications(caller: UserId, spaceId: SpaceId, level: NotificationLevel): Resource<MeView> =
        setNotifications(caller, spaceId, level)

    suspend fun payment(caller: UserId, spaceId: SpaceId): Resource<PaymentView> = startMembershipPayment(caller, spaceId)
}
