package com.circaso.users.domain.models

import com.circaso.shared.PhoneNumber
import com.circaso.shared.UserId
import com.circaso.users.api.PublicProfile
import java.time.Instant
import java.util.UUID

enum class AvatarVisibility { EVERYONE_WHO_CAN_REACH, CONTACTS, NOBODY }

enum class AboutVisibility { EVERYONE_WHO_CAN_REACH, CONTACTS, NOBODY }

/** Defaults (Users → Privacy defaults): the avatar to anyone who can reach you, the about line to contacts, handle lookup on. */
data class Privacy(
    val avatar: AvatarVisibility = AvatarVisibility.EVERYONE_WHO_CAN_REACH,
    val about: AboutVisibility = AboutVisibility.CONTACTS,
    val handleLookup: Boolean = true,
)

data class Profile(
    val userId: UserId,
    val displayName: String,
    val about: String?,
    val handle: String?,
    val handleChangedAt: Instant?,
    val avatarKey: String?,
    val privacy: Privacy,
    val createdAt: Instant,
    val version: Long = 0,
)

enum class ContactSource { PHONEBOOK, QR, INVITE, HANDLE }

enum class ContactStatus { ACTIVE, REMOVED }

data class Contact(
    val ownerId: UserId,
    val contactId: UserId,
    val source: ContactSource,
    val status: ContactStatus,
    val removedAt: Instant?,
    val addedAt: Instant,
)

data class HandleClaim(
    val handle: String,
    val userId: UserId,
    val claimedAt: Instant,
    val releasedAt: Instant?,
)

data class Block(val userId: UserId, val blockedId: UserId, val blockedAt: Instant)

data class Invite(
    val token: String,
    val ownerId: UserId,
    val kind: String,
    val createdAt: Instant,
)

data class WaitingContact(val phoneHmac: String, val ownerId: UserId, val addedAt: Instant)

/** A user's contact with the profile that goes with it. */
data class ContactEntry(val contact: Contact, val profile: PublicProfile)

data class ContactPage(val entries: List<ContactEntry>, val nextCursor: String?)

enum class HandleAvailability { AVAILABLE, TAKEN, INVALID }

data class HandleCheck(val handle: String, val availability: HandleAvailability, val suggestion: String?)

enum class ConnectVia { HANDLE, QR, INVITE }

data class Connection(val user: PublicProfile, val created: Boolean)

data class InviteLink(
    val token: String,
    val url: String,
    val qrPayload: String,
    val expiresAt: Instant,
)

data class SyncMatch(val phone: PhoneNumber, val user: PublicProfile)

data class UploadTarget(val uploadUrl: String, val objectKey: String, val expiresInSeconds: Long)

/** What the app is told about its own profile, with the time it may next change its handle. */
data class MyProfile(val profile: Profile, val avatarUrl: String?, val handleChangeableAt: Instant?)

data class ProfileChange(
    val displayName: String?,
    val about: String?,
    val clearAbout: Boolean,
    val avatarVisibility: AvatarVisibility?,
    val aboutVisibility: AboutVisibility?,
    val handleLookup: Boolean?,
    val version: Long,
)

data class SyncCommand(
    val userId: UserId,
    val syncId: UUID,
    val batch: Int,
    val final: Boolean,
    val phones: Set<PhoneNumber>,
)
