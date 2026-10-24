package com.circaso.users.presentation.services

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.api.PublicProfile
import com.circaso.users.domain.models.MyProfile
import com.circaso.users.domain.models.ProfileChange
import com.circaso.users.domain.models.UploadTarget
import com.circaso.users.domain.usecases.GetMyProfile
import com.circaso.users.domain.usecases.GetPublicProfile
import com.circaso.users.domain.usecases.PrepareAvatarUpload
import com.circaso.users.domain.usecases.RemoveAvatar
import com.circaso.users.domain.usecases.SetAvatar
import com.circaso.users.domain.usecases.UpdateProfile

/** Own profile, avatar and other people's public profiles. */
class ProfileService(
    private val getMyProfile: GetMyProfile,
    private val updateProfile: UpdateProfile,
    private val prepareAvatarUpload: PrepareAvatarUpload,
    private val setAvatar: SetAvatar,
    private val removeAvatar: RemoveAvatar,
    private val getPublicProfile: GetPublicProfile,
) {
    suspend fun getMine(userId: UserId): Resource<MyProfile> = getMyProfile(userId)

    suspend fun update(userId: UserId, change: ProfileChange): Resource<MyProfile> = updateProfile(userId, change)

    suspend fun prepareAvatar(
        userId: UserId,
        contentType: String,
        sizeBytes: Long,
    ): Resource<UploadTarget> =
        prepareAvatarUpload(
            userId,
            contentType,
            sizeBytes,
        )

    suspend fun setAvatar(userId: UserId, objectKey: String): Resource<MyProfile> = setAvatar.invoke(userId, objectKey)

    suspend fun removeAvatar(userId: UserId): Resource<MyProfile> = removeAvatar.invoke(userId)

    suspend fun getPublic(viewer: UserId, target: UserId): Resource<PublicProfile> = getPublicProfile(viewer, target)
}
