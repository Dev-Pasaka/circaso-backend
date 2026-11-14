package com.circaso.share.domain.usecases

import com.circaso.messaging.api.GroupLinkPages
import com.circaso.messaging.api.GroupLinkToken
import com.circaso.share.domain.models.GroupPageLookup
import com.circaso.share.domain.ports.ViewThrottle
import com.circaso.share.domain.support.ShareConfig
import com.circaso.shared.Resource
import java.time.Duration

/**
 * The public page of a group join link at `/s/{token}` (decision 2026-11-14), for anyone with the link and the chat
 * apps that draw its preview. The link and what it shows are messaging's (the preview an admin's phone published);
 * share only draws the page. Views count against the same per-address limit as every other page, before the token is
 * read, so tokens can't be walked. A link that was reset or turned off looks the same as one that never was.
 */
class ViewGroupLinkPage(
    private val groupLinks: GroupLinkPages,
    private val throttle: ViewThrottle,
    private val config: ShareConfig,
) {
    suspend operator fun invoke(token: GroupLinkToken, callerAddress: String): Resource<GroupPageLookup> {
        throttle.take(callerAddress, config.viewsPerMinute, MINUTE)?.let {
            return Resource.success(GroupPageLookup.Throttled(it))
        }
        val page = groupLinks.page(token) ?: return Resource.success(GroupPageLookup.Gone)
        return Resource.success(GroupPageLookup.Live(token, page))
    }

    private companion object {
        val MINUTE: Duration = Duration.ofMinutes(1)
    }
}
