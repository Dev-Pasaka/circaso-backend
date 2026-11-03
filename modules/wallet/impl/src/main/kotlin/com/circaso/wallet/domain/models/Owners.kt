package com.circaso.wallet.domain.models

import com.circaso.messaging.api.ChatId
import com.circaso.shared.UserId
import com.circaso.wallet.api.WalletId
import com.circaso.wallet.api.WalletOwnerType
import java.time.Instant
import java.util.UUID

/**
 * Whose a wallet is (decision 2026-11-03, Spaces phase 2). Every wallet is filed under its [holder] id in the wallet's
 * tables: a person's own wallet under their user id (so nothing written before group wallets changes), a group
 * wallet under its own [WalletId]. The holder is typed [UserId] because those columns always were; outside the
 * wallet a group wallet is only ever named by its [WalletId] and its group.
 */
sealed interface WalletOwner {
    val walletId: WalletId
    val type: WalletOwnerType

    /** The one person who can sign for the wallet (option A): the person themselves, or the group wallet's owner. */
    val signerId: UserId

    val holder: UserId get() = UserId(walletId.value)

    data class Person(val userId: UserId) : WalletOwner {
        override val walletId get() = WalletId(userId.value)
        override val type get() = WalletOwnerType.PERSON
        override val signerId get() = userId
    }

    /** A group's wallet: [signerId] set its PIN; the group's admins approve each payout before the signer pays it. */
    data class Group(
        override val walletId: WalletId,
        val chatId: ChatId,
        override val signerId: UserId,
        val createdBy: UserId,
        val createdAt: Instant,
    ) : WalletOwner {
        override val type get() = WalletOwnerType.GROUP
    }
}

/** The holder id of a group wallet, for the wallet's own tables. */
fun WalletId.asHolder(): UserId = UserId(value)

/**
 * A wallet the caller may see (their own, or the wallet of a group they are in), and how: [owner] says whose it is,
 * [canSign] whether the caller is the one who signs for it (W3, W4, W12).
 */
data class WalletAccess(val owner: WalletOwner, val canSign: Boolean)

/** W14 `POST /wallet/groups/{chatId}`: the group owner sets up the group's wallet. */
data class CreateGroupWalletCommand(val userId: UserId, val chatId: ChatId, val idempotencyKey: UUID)

/** W14's answer: the group's wallet and, while its PIN isn't set, the challenge that sets it. */
data class GroupWalletSetup(val owner: WalletOwner.Group, val setup: WalletSetup)

/**
 * A wallet as the switcher (W16) and a group's info (W15) list it: whose it is, where it stands (from our rows, never
 * Circle), whether the caller signs for it, and the chains it can be paid on now.
 */
data class WalletListing(
    val owner: WalletOwner,
    val status: WalletStatus,
    val canSign: Boolean,
    val blockchains: List<String>,
)

/** W15's answer: the group's wallet, or none yet ([listing] null, so `status` is `NONE`). */
data class GroupWalletView(val chatId: ChatId, val listing: WalletListing?)
