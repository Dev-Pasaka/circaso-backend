package com.circaso.messaging.api

import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import com.circaso.shared.UuidIdSerializer
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.UUID

/** Identifies one chat, direct or group. Ids come from `OrderedIds`, so chats and messages list in order. */
@Serializable(with = ChatId.Serializer::class)
@JvmInline
value class ChatId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): ChatId = ChatId(Uuid7.generate())

        fun parse(raw: String): ChatId? = Uuid7.parse(raw)?.let(::ChatId)
    }

    object Serializer : UuidIdSerializer<ChatId>("ChatId", ::ChatId, ChatId::value)
}

/**
 * Who is in a chat, for the modules that act inside one: wallet (pay-in-chat) and calls (group calls). A chat the
 * user isn't in looks the same as one that doesn't exist.
 */
interface ChatDirectory {
    suspend fun isMember(chatId: ChatId, userId: UserId): Boolean

    suspend fun members(chatId: ChatId): Set<UserId>

    /** The owner and admins of a group, for the modules whose actions need an admin (chama approvals). */
    suspend fun admins(chatId: ChatId): Set<UserId>

    /** Whether the chat is a group (false for a direct chat, and for one that doesn't exist), for calls. */
    suspend fun isGroup(chatId: ChatId): Boolean

    /** The groups [userId] is in, for chama's list of the user's chamas (Asha, decision 2026-10-31). */
    suspend fun groupsOf(userId: UserId): List<ChatId>

    /**
     * The group's owner, for the wallet (only the owner sets up a group wallet, decision 2026-11-03) and chama (only
     * the owner chooses who approves payouts). Null for a direct chat and for one that doesn't exist.
     */
    suspend fun owner(chatId: ChatId): UserId?
}

/**
 * Asked by messaging about a private chat with a space's owner (Spaces phase 3, M4 `viaSpaceId`): an end-to-end
 * encrypted direct chat between a person and the owner, separate from any other chat between the two. Spaces
 * implements it and binds it, as chama does the wallet's guard; spaces' id is a UUID, since spaces' api can't be named
 * by this one. Blocks are still messaging's own check.
 */
interface SpacePrivateChats {
    /**
     * Whether [from] may open, or write in, the private chat with [to] via [spaceId]: one of them owns the live space,
     * the other may message its owner (its `privateMessages` setting) and neither is banned from it.
     */
    suspend fun allowed(
        spaceId: UUID,
        from: UserId,
        to: UserId,
    ): Boolean
}

/**
 * The token that names a group's join link: the last segment of `<SHARE_BASE_URL>/s/<token>` (decision 2026-11-14).
 * Twenty-two base62 characters from a secure random source (about 131 bits), so it can be handed out in a chat app
 * but never guessed or walked. Its length sets it apart from a share code (12 characters), which is how the share
 * host tells the two apart. Never logged: whoever has it can see the group's published preview and join.
 */
@JvmInline
value class GroupLinkToken(val value: String) {
    override fun toString(): String = "GroupLinkToken(***)"

    companion object {
        const val LENGTH = 22
        private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        private val random = SecureRandom()
        private val shape = Regex("[0-9A-Za-z]{$LENGTH}")

        fun new(): GroupLinkToken =
            GroupLinkToken(String(CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }))

        /** A token in the exact shape [new] makes, or null: anything else can't be a join link. */
        fun parse(raw: String?): GroupLinkToken? = raw?.takeIf { shape.matches(it) }?.let(::GroupLinkToken)
    }
}

/**
 * What a group's join link shows to anyone who has it: the preview an admin's phone published for that link (the
 * server can't read the group's sealed name), and how many people are in the group. Never members, messages or money.
 * [photoVersion] changes whenever the published photo does, so a cached picture is never shown for a new one.
 */
data class GroupLinkPage(
    val name: String,
    val description: String?,
    val hasPhoto: Boolean,
    val photoVersion: Long,
    val memberCount: Int,
)

/** A link's published photo: a small JPEG or PNG, as the admin's phone sent it. */
class GroupLinkPhoto(val bytes: ByteArray, val contentType: String)

/**
 * The public side of group join links, for share, which draws the page at `/s/{token}` and serves its photo. A link
 * that was reset or turned off looks the same as one that never was: null.
 */
interface GroupLinkPages {
    suspend fun page(token: GroupLinkToken): GroupLinkPage?

    suspend fun photo(token: GroupLinkToken): GroupLinkPhoto?
}
