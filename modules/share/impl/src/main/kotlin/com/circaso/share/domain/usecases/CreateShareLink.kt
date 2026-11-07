package com.circaso.share.domain.usecases

import com.circaso.share.api.ShareCode
import com.circaso.share.domain.errors.ShareError
import com.circaso.share.domain.models.CreateShareCommand
import com.circaso.share.domain.models.ShareKind
import com.circaso.share.domain.models.ShareLink
import com.circaso.share.domain.repositories.LinkRepository
import com.circaso.share.domain.support.Built
import com.circaso.share.domain.support.ShareConfig
import com.circaso.share.domain.support.Snapshots
import com.circaso.shared.Resource
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * SH1: a short link to a page showing what the caller chose. The snapshot is built from the owning module's records
 * ([Snapshots]); the link ends when the caller said (never, by default), and an invite's link no later than its
 * invite. A code that is already taken (about one in 10^21) is drawn again.
 */
class CreateShareLink(
    private val links: LinkRepository,
    private val snapshots: Snapshots,
    private val config: ShareConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: CreateShareCommand): Resource<ShareLink> {
        val built =
            when (val r = build(command)) {
                is Resource.Error -> return r
                is Resource.Success -> r.data
            }
        val now = clock.instant()
        val endsAt = earliest(command.endsAfterDays?.let { now.plus(Duration.ofDays(it.toLong())) }, built.endsBy)
        repeat(DRAWS) {
            val link = ShareLink(ShareCode.new(), command.ownerId, command.kind, built.content, now, endsAt, null)
            if (links.insert(link, expiresAt = endsAt?.plus(config.tombstone))) return Resource.success(link)
        }
        return Resource.error(ShareError.Unavailable)
    }

    private suspend fun build(command: CreateShareCommand): Resource<Built> =
        when (command.kind) {
            ShareKind.RECEIPT -> {
                val transferId = command.transferId ?: return Resource.error(ShareError.RequestInvalid)
                snapshots.receipt(command.ownerId, transferId, command.showNames, command.currency)
            }

            ShareKind.STATEMENT -> {
                val chatId = command.chatId ?: return Resource.error(ShareError.RequestInvalid)
                snapshots.statement(command.ownerId, chatId, command.showNames, command.currency)
            }

            ShareKind.INVITE -> {
                snapshots.invite(command.ownerId)
            }

            ShareKind.PAY_ME -> {
                snapshots.payMe(command.ownerId)
            }

            ShareKind.ADDRESS -> {
                val blockchain = command.blockchain ?: return Resource.error(ShareError.RequestInvalid)
                snapshots.address(command.ownerId, blockchain, command.showNames)
            }
        }

    private fun earliest(a: Instant?, b: Instant?): Instant? =
        when {
            a == null -> b
            b == null -> a
            else -> minOf(a, b)
        }

    private companion object {
        const val DRAWS = 3
    }
}
