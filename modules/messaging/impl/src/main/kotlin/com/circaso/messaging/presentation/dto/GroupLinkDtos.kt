package com.circaso.messaging.presentation.dto

import com.circaso.shared.InstantSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * M29: what the link's page shows, published by an admin's phone (the server can't read the group's sealed name).
 * `photo` is base64 of a small JPEG or PNG (at most 64 KB), null when the group has none. `reset` replaces the link
 * with a new one; the old one stops working.
 */
@Serializable
data class PublishGroupLinkRequest(
    val name: String,
    val description: String? = null,
    val photo: String? = null,
    val reset: Boolean = false,
)

/**
 * A group's join link, for its admins. `url` is what they share; `name`, `description` and `photoUrl` are the copy
 * published for the link's page, so a phone can tell when it is out of date.
 */
@Serializable
data class GroupLinkDto(
    val token: String,
    val url: String,
    val name: String,
    val description: String? = null,
    val photoUrl: String? = null,
    val createdBy: String,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
    @Serializable(with = InstantSerializer::class) val updatedAt: Instant,
)

/** M28: `link` is null when the group has none. */
@Serializable
data class GroupLinkResponse(val link: GroupLinkDto? = null)

@Serializable
data class GroupLinkOffResponse(val off: Boolean)

/**
 * M31: a join link as a signed-in person sees it before joining. `chatId` is set (and `member` true) only when they
 * are already in the group. Never the group's members or messages.
 */
@Serializable
data class GroupLinkPreviewResponse(
    val name: String,
    val description: String? = null,
    val photoUrl: String? = null,
    val memberCount: Int,
    val member: Boolean,
    val chatId: String? = null,
)
