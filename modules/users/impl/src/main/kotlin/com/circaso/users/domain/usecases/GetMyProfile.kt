package com.circaso.users.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.domain.models.MyProfile
import com.circaso.users.domain.support.HANDLE_COOLDOWN
import com.circaso.users.domain.support.ProfileProvisioner
import com.circaso.users.domain.support.ProfileViews

/** U1: the caller's own full profile, privacy settings included. */
class GetMyProfile(private val provisioner: ProfileProvisioner, private val views: ProfileViews) {
    suspend operator fun invoke(userId: UserId): Resource<MyProfile> {
        val profile = provisioner.ensure(userId)
        return Resource.success(MyProfile(profile, views.avatarUrl(profile), profile.handleChangedAt?.plus(HANDLE_COOLDOWN)))
    }
}
