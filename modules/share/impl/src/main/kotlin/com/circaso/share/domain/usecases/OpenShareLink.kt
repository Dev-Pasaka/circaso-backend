package com.circaso.share.domain.usecases

import com.circaso.messaging.api.ChatDirectory
import com.circaso.share.api.ShareCode
import com.circaso.share.domain.errors.ShareError
import com.circaso.share.domain.models.OpenTarget
import com.circaso.share.domain.models.OpenedLink
import com.circaso.share.domain.models.ShareContent
import com.circaso.share.domain.models.ShareLink
import com.circaso.share.domain.repositories.LinkRepository
import com.circaso.share.domain.support.Snapshots
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.api.Wallets
import java.time.Clock

/**
 * SH4: a link opened in the app by a signed-in person. They get what the page shows, whether they made it, and where
 * their app may go from it, decided for them: their own payment, a group they are in, the sharer's invite or "pay
 * me". A receipt still on its way is brought up to date first.
 */
class OpenShareLink(
    private val links: LinkRepository,
    private val snapshots: Snapshots,
    private val wallets: Wallets,
    private val chats: ChatDirectory,
    private val clock: Clock,
) {
    suspend operator fun invoke(viewer: UserId, code: ShareCode): Resource<OpenedLink> {
        val found = links.find(code, clock.instant())
        if (found == null || !found.isLive(clock.instant())) return Resource.error(ShareError.LinkNotFound)
        val link = refreshed(found)
        val isMine = link.ownerId == viewer
        return Resource.success(OpenedLink(link, isMine, target(link, viewer, isMine)))
    }

    private suspend fun refreshed(link: ShareLink): ShareLink {
        val receipt = link.content as? ShareContent.Receipt ?: return link
        val fresh = snapshots.refresh(receipt) ?: return link
        links.updateContent(link.code, fresh)
        return link.copy(content = fresh)
    }

    private suspend fun target(
        link: ShareLink,
        viewer: UserId,
        isMine: Boolean,
    ): OpenTarget? =
        when (val content = link.content) {
            is ShareContent.Receipt -> {
                val transfer = wallets.transfer(content.transferId)
                val parties = setOfNotNull(transfer?.senderId, transfer?.recipientId, transfer?.signerId)
                if (viewer in parties) OpenTarget.Transfer(content.transferId) else null
            }

            is ShareContent.Statement -> {
                if (chats.isMember(content.chatId, viewer)) OpenTarget.Chama(content.chatId) else null
            }

            is ShareContent.Person -> {
                when {
                    isMine -> {
                        null
                    }

                    content.inviteToken != null -> {
                        OpenTarget.Invite(
                            content.userId,
                            content.displayName,
                            content.inviteToken,
                        )
                    }

                    else -> {
                        OpenTarget.Pay(content.userId, content.displayName, content.handle)
                    }
                }
            }

            is ShareContent.Address -> {
                if (isMine) null else OpenTarget.PayAddress(content.address, content.blockchain)
            }

            null -> {
                null
            }
        }
}
