package com.circaso.share.domain.usecases

import com.circaso.messaging.api.GroupLinkPage
import com.circaso.messaging.api.GroupLinkPages
import com.circaso.messaging.api.GroupLinkToken
import com.circaso.share.domain.errors.ShareError
import com.circaso.shared.Resource

/**
 * SH4 for a group join link (decision 2026-11-14). The current app opens these with messaging's M31 and M32; this
 * answers an app from before join links, which sends every `/s/…` link here: it gets the group's published name under
 * a kind it doesn't know, which it shows as a plain shared link with a line saying to update. A link that isn't live
 * answers `share.link_not_found`, as any other.
 */
class OpenGroupLink(private val groupLinks: GroupLinkPages) {
    suspend operator fun invoke(token: GroupLinkToken): Resource<GroupLinkPage> =
        groupLinks.page(token)?.let { Resource.success(it) } ?: Resource.error(ShareError.LinkNotFound)
}
