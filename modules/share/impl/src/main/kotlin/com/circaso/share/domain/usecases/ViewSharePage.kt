package com.circaso.share.domain.usecases

import com.circaso.share.api.ShareCode
import com.circaso.share.domain.models.PageLookup
import com.circaso.share.domain.models.ShareContent
import com.circaso.share.domain.ports.ViewThrottle
import com.circaso.share.domain.repositories.LinkRepository
import com.circaso.share.domain.support.ShareConfig
import com.circaso.share.domain.support.Snapshots
import com.circaso.shared.Resource
import java.time.Clock
import java.time.Duration

/**
 * The public page at `/s/{code}`, for anyone with the link (and the chat apps that draw its preview). Each caller
 * address may open [ShareConfig.viewsPerMinute] pages a minute, counted before the code is even read, so codes can't
 * be walked. A revoked or ended link is [PageLookup.Gone]; a code that never was, [PageLookup.Missing]. A receipt
 * still on its way is brought up to date, as in the app.
 */
class ViewSharePage(
    private val links: LinkRepository,
    private val snapshots: Snapshots,
    private val throttle: ViewThrottle,
    private val config: ShareConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(rawCode: String?, callerAddress: String): Resource<PageLookup> {
        throttle.take(callerAddress, config.viewsPerMinute, MINUTE)?.let {
            return Resource.success(PageLookup.Throttled(it))
        }
        val code = ShareCode.parse(rawCode) ?: return Resource.success(PageLookup.Missing)
        val now = clock.instant()
        val link = links.find(code, now) ?: return Resource.success(PageLookup.Missing)
        if (!link.isLive(now)) return Resource.success(PageLookup.Gone)
        val receipt = link.content as? ShareContent.Receipt ?: return Resource.success(PageLookup.Live(link))
        val fresh = snapshots.refresh(receipt) ?: return Resource.success(PageLookup.Live(link))
        links.updateContent(link.code, fresh)
        return Resource.success(PageLookup.Live(link.copy(content = fresh)))
    }

    private companion object {
        val MINUTE: Duration = Duration.ofMinutes(1)
    }
}
