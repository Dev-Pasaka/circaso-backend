package com.circaso.users.data.mappers

import com.circaso.shared.CircasoJson
import com.circaso.shared.UserId
import com.circaso.users.data.entities.ContactRow
import com.circaso.users.data.entities.HandleClaimRow
import com.circaso.users.data.entities.BlockRow
import com.circaso.users.data.entities.InviteRow
import com.circaso.users.data.entities.ProfileRow
import com.circaso.users.data.entities.WaitingContactRow
import com.circaso.users.domain.models.AboutVisibility
import com.circaso.users.domain.models.AvatarVisibility
import com.circaso.users.domain.models.Block
import com.circaso.users.domain.models.Contact
import com.circaso.users.domain.models.ContactSource
import com.circaso.users.domain.models.ContactStatus
import com.circaso.users.domain.models.HandleClaim
import com.circaso.users.domain.models.Invite
import com.circaso.users.domain.models.Privacy
import com.circaso.users.domain.models.Profile
import com.circaso.users.domain.models.WaitingContact
import kotlinx.serialization.Serializable
import java.time.Instant

/** How the privacy settings are stored in the `privacy` column. */
@Serializable
private data class PrivacyJson(val avatar: String, val about: String, val handleLookup: Boolean)

private fun Privacy.toJson() = CircasoJson.encodeToString(PrivacyJson.serializer(), PrivacyJson(avatar.name, about.name, handleLookup))

private fun privacyOf(json: String): Privacy = runCatching {
    CircasoJson.decodeFromString(PrivacyJson.serializer(), json).let {
        Privacy(
            runCatching { AvatarVisibility.valueOf(it.avatar) }.getOrDefault(AvatarVisibility.EVERYONE_WHO_CAN_REACH),
            runCatching { AboutVisibility.valueOf(it.about) }.getOrDefault(AboutVisibility.CONTACTS),
            it.handleLookup,
        )
    }
}.getOrDefault(Privacy())

// A plain save or update writes nothing for a null column, so clearing a value would leave the old one in place.
// The columns that can be cleared store an empty text (or the epoch) for "none" and read it back as null.
fun ProfileRow.toModel() = Profile(
    UserId(userId), displayName, about?.takeIf { it.isNotEmpty() }, handle, handleChangedAt, avatarKey?.takeIf { it.isNotEmpty() },
    privacyOf(privacy), createdAt, version,
)

fun Profile.toRow() = ProfileRow(userId.value, displayName, about ?: "", handle, handleChangedAt, avatarKey ?: "", privacy.toJson(), createdAt, null, version)

fun ContactRow.toModel() = Contact(
    UserId(ownerId), UserId(contactId), ContactSource.valueOf(source), ContactStatus.valueOf(status),
    removedAt?.takeIf { it != Instant.EPOCH }, addedAt,
)

fun Contact.toRow() = ContactRow(ownerId.value, contactId.value, source.name, status.name, removedAt ?: Instant.EPOCH, addedAt)

fun HandleClaimRow.toModel() = HandleClaim(handle, UserId(userId), claimedAt, releasedAt)

fun BlockRow.toModel() = Block(UserId(userId), UserId(blockedId), blockedAt)

fun InviteRow.toModel() = Invite(token, UserId(ownerId), kind, createdAt)

fun WaitingContactRow.toModel() = WaitingContact(phoneHmac, UserId(ownerId), addedAt)

@Suppress("unused")
private val keep = Instant.EPOCH
