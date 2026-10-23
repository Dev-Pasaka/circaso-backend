package com.circaso.users.domain.usecases

import com.circaso.identity.api.PhoneChanged
import com.circaso.shared.Resource
import com.circaso.users.domain.support.ProfileProvisioner
import com.circaso.users.domain.support.UserNotifier
import com.circaso.users.domain.support.WaitingContactMatcher

/** `identity.PhoneChanged`: contacts waiting for the new number are connected; existing contacts stay. */
class RecheckWaitingContacts(
    private val provisioner: ProfileProvisioner,
    private val matcher: WaitingContactMatcher,
    private val notifier: UserNotifier,
) {
    suspend operator fun invoke(event: PhoneChanged): Resource<Unit> {
        val profile = provisioner.ensure(event.userId)
        matcher.match(event.newPhoneHmac, event.userId).forEach { notifier.contactJoined(it, profile) }
        return Resource.success(Unit)
    }
}
