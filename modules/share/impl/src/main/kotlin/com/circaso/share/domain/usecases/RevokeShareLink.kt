package com.circaso.share.domain.usecases

import com.circaso.share.api.ShareCode
import com.circaso.share.domain.errors.ShareError
import com.circaso.share.domain.repositories.LinkRepository
import com.circaso.share.domain.support.ShareConfig
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Clock

/**
 * SH3: the sharer turns a link off. Its snapshot is cleared at once; the row stays for [ShareConfig.tombstone] so the
 * page says the link was turned off, then the purge job deletes it. Someone else's link, or one already off, answers
 * `share.link_not_found`; a retry with the same key gets the first answer from the gateway.
 */
class RevokeShareLink(private val links: LinkRepository, private val config: ShareConfig, private val clock: Clock) {
    suspend operator fun invoke(ownerId: UserId, code: ShareCode): Resource<Unit> {
        val now = clock.instant()
        val revoked = links.revoke(code, ownerId, now, keepUntil = now.plus(config.tombstone))
        return if (revoked) Resource.success(Unit) else Resource.error(ShareError.LinkNotFound)
    }
}
