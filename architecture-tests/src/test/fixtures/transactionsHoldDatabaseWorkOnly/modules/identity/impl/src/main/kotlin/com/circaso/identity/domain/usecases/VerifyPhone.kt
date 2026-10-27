package com.circaso.identity.domain.usecases

import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.integrations.api.SmsGateway
import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.api.ReachPolicy

// Each of these holds a pooled connection while it waits on something outside the database.
class VerifyPhone(
    private val transactor: Transactor,
    private val accounts: AccountRepository,
    private val events: EventBus,
    private val sms: SmsGateway,
    private val reach: ReachPolicy,
) {
    suspend operator fun invoke(userId: UserId): Resource<Unit> {
        transactor.run {
            val account = accounts.find(userId)
            if (account != null) {
                sms.send(account.phone, "verified")
                reach.canReach(userId, userId)
            }
            events.publish(events.envelope(PhoneVerified(userId), PhoneVerified.serializer()))
        }
        return Resource.success(Unit)
    }
}
