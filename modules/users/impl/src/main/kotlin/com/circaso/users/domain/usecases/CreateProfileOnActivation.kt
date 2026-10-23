package com.circaso.users.domain.usecases

import com.circaso.identity.api.AccountActivated
import com.circaso.shared.Resource
import com.circaso.users.domain.support.ProfileProvisioner
import com.circaso.users.domain.support.UserNotifier
import com.circaso.users.domain.support.WaitingContactMatcher

/**
 * `identity.AccountActivated`: creates the profile (named from the provider's hint) and turns the contacts that
 * were waiting for this number into contacts, telling each with `contact.joined`. Safe to run twice.
 *
 * TODO(decision: the avatar hint is an external URL, not a file of ours, so it is not copied into the profile)
 */
class CreateProfileOnActivation(
    private val provisioner: ProfileProvisioner,
    private val matcher: WaitingContactMatcher,
    private val notifier: UserNotifier,
) {
    suspend operator fun invoke(event: AccountActivated): Resource<Unit> {
        val profile = provisioner.ensure(event.userId, event.hints.displayName)
        matcher.match(event.phoneHmac, event.userId).forEach { notifier.contactJoined(it, profile) }
        return Resource.success(Unit)
    }
}
