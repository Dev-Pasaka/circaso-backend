package com.circaso.share.presentation.services

import com.circaso.messaging.api.GroupLinkPage
import com.circaso.messaging.api.GroupLinkToken
import com.circaso.share.api.ShareCode
import com.circaso.share.domain.models.CreateShareCommand
import com.circaso.share.domain.models.LinkCursor
import com.circaso.share.domain.models.LinkPage
import com.circaso.share.domain.models.OpenedLink
import com.circaso.share.domain.models.ShareLink
import com.circaso.share.domain.usecases.CreateShareLink
import com.circaso.share.domain.usecases.ListShareLinks
import com.circaso.share.domain.usecases.OpenGroupLink
import com.circaso.share.domain.usecases.OpenShareLink
import com.circaso.share.domain.usecases.RevokeShareLink
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** The app's share links: SH1 make one, SH2 list yours, SH3 turn one off, SH4 open one. */
class ShareService(
    private val createShareLink: CreateShareLink,
    private val listShareLinks: ListShareLinks,
    private val revokeShareLink: RevokeShareLink,
    private val openShareLink: OpenShareLink,
    private val openGroupLink: OpenGroupLink,
) {
    suspend fun create(command: CreateShareCommand): Resource<ShareLink> = createShareLink(command)

    suspend fun list(
        ownerId: UserId,
        after: LinkCursor?,
        limit: Int,
    ): Resource<LinkPage> = listShareLinks(ownerId, after, limit)

    suspend fun revoke(ownerId: UserId, code: ShareCode): Resource<Unit> = revokeShareLink(ownerId, code)

    suspend fun open(viewer: UserId, code: ShareCode): Resource<OpenedLink> = openShareLink(viewer, code)

    /** SH4 for a group join link, for an app from before them (decision 2026-11-14). */
    suspend fun openGroup(token: GroupLinkToken): Resource<GroupLinkPage> = openGroupLink(token)
}
