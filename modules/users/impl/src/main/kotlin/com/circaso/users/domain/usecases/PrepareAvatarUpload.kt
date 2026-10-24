package com.circaso.users.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.domain.errors.UsersError
import com.circaso.users.domain.models.UploadTarget
import com.circaso.users.domain.ports.AvatarStorage
import com.circaso.users.domain.support.UsersConfig

val AVATAR_TYPES = setOf("image/jpeg", "image/png", "image/webp")

/** U3: a pre-signed upload URL for a JPEG, PNG or WebP up to 5 MB. The backend never streams the image. */
class PrepareAvatarUpload(private val storage: AvatarStorage, private val config: UsersConfig) {
    suspend operator fun invoke(
        userId: UserId,
        contentType: String,
        sizeBytes: Long,
    ): Resource<UploadTarget> {
        if (contentType !in AVATAR_TYPES || sizeBytes <= 0 ||
            sizeBytes > config.maxAvatarBytes
        ) {
            return Resource.error(UsersError.AvatarInvalid)
        }
        return when (val target = storage.prepareUpload(userId, contentType, config.avatarUploadTtl)) {
            is Resource.Success -> {
                Resource.success(
                    UploadTarget(target.data.uploadUrl, target.data.objectKey, config.avatarUploadTtl.seconds),
                )
            }

            is Resource.Error -> {
                target
            }
        }
    }
}
