package com.circaso.messaging.presentation.services

import com.circaso.messaging.domain.usecases.RetireReplacedPhone
import com.circaso.shared.DeviceId
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Instant

/** What messaging does with identity's events. MS6 adds the account status and deletion events. */
class IdentityEventsService(private val retireReplacedPhone: RetireReplacedPhone) {
    suspend fun deviceReplaced(
        userId: UserId,
        oldDeviceId: DeviceId,
        replacedAt: Instant,
    ): Resource<Unit> =
        retireReplacedPhone(
            userId,
            oldDeviceId,
            replacedAt,
        )
}
