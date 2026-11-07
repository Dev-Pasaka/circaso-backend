package com.circaso.share.domain.usecases

import com.circaso.share.domain.models.LinkCursor
import com.circaso.share.domain.models.LinkPage
import com.circaso.share.domain.repositories.LinkRepository
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Clock

/** SH2: the caller's links that still open, newest first, a page at a time. Ended and revoked ones aren't listed. */
class ListShareLinks(private val links: LinkRepository, private val clock: Clock) {
    suspend operator fun invoke(
        ownerId: UserId,
        after: LinkCursor?,
        limit: Int,
    ): Resource<LinkPage> {
        val page = links.listLive(ownerId, clock.instant(), after, limit + 1)
        val shown = page.take(limit)
        val next = if (page.size > limit) shown.last().let { LinkCursor(it.createdAt, it.code) } else null
        return Resource.success(LinkPage(shown, next))
    }
}
