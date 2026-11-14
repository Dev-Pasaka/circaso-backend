package com.circaso.share.domain.usecases

import com.circaso.messaging.api.GroupLinkPages
import com.circaso.messaging.api.GroupLinkPhoto
import com.circaso.messaging.api.GroupLinkToken
import com.circaso.share.domain.ports.ViewThrottle
import com.circaso.share.domain.support.ShareConfig
import com.circaso.shared.Resource
import java.time.Duration

/**
 * A join link's published photo at `/s/{token}/photo`, for its page, its link preview and the app's join sheet.
 * Counted against the caller address's page views like the page itself; null when throttled, when the link isn't
 * live or when it has no photo.
 */
class ViewGroupLinkPhoto(
    private val groupLinks: GroupLinkPages,
    private val throttle: ViewThrottle,
    private val config: ShareConfig,
) {
    suspend operator fun invoke(token: GroupLinkToken, callerAddress: String): Resource<GroupLinkPhoto?> {
        if (throttle.take(callerAddress, config.viewsPerMinute, MINUTE) != null) return Resource.success(null)
        return Resource.success(groupLinks.photo(token))
    }

    private companion object {
        val MINUTE: Duration = Duration.ofMinutes(1)
    }
}
