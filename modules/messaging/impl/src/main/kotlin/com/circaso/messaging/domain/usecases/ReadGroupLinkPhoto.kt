package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.GroupLinkPhoto
import com.circaso.messaging.api.GroupLinkToken
import com.circaso.messaging.domain.repositories.GroupLinkRepository
import com.circaso.messaging.domain.support.LinkPreviews
import com.circaso.shared.Resource

/** A live join link's published photo (`GroupLinkPages.photo`, for share); null when it has none or isn't live. */
class ReadGroupLinkPhoto(private val links: GroupLinkRepository) {
    suspend operator fun invoke(token: GroupLinkToken): Resource<GroupLinkPhoto?> {
        val photo =
            links
                .find(token)
                ?.takeIf { it.isLive }
                ?.preview
                ?.photo ?: return Resource.success(null)
        val type = LinkPreviews.contentTypeOf(photo) ?: return Resource.success(null)
        return Resource.success(GroupLinkPhoto(photo, type))
    }
}
