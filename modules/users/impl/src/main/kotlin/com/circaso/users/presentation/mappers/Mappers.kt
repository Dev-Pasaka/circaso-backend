package com.circaso.users.presentation.mappers

import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.users.api.PublicProfile
import com.circaso.users.domain.models.AboutVisibility
import com.circaso.users.domain.models.AvatarVisibility
import com.circaso.users.domain.models.ConnectVia
import com.circaso.users.domain.models.Connection
import com.circaso.users.domain.models.ContactPage
import com.circaso.users.domain.models.HandleAvailability
import com.circaso.users.domain.models.HandleCheck
import com.circaso.users.domain.models.InviteLink
import com.circaso.users.domain.models.MyProfile
import com.circaso.users.domain.models.ProfileChange
import com.circaso.users.domain.models.SyncCommand
import com.circaso.users.domain.models.SyncMatch
import com.circaso.users.domain.models.UploadTarget
import com.circaso.users.presentation.dto.AgentContactDto
import com.circaso.users.presentation.dto.AgentContactsResponse
import com.circaso.users.presentation.dto.BlocksResponse
import com.circaso.users.presentation.dto.ConnectRequest
import com.circaso.users.presentation.dto.ConnectResponse
import com.circaso.users.presentation.dto.ContactDto
import com.circaso.users.presentation.dto.ContactsResponse
import com.circaso.users.presentation.dto.HandleAvailabilityResponse
import com.circaso.users.presentation.dto.InviteResponse
import com.circaso.users.presentation.dto.MyProfileResponse
import com.circaso.users.presentation.dto.PrivacyDto
import com.circaso.users.presentation.dto.PublicProfileDto
import com.circaso.users.presentation.dto.SyncMatchDto
import com.circaso.users.presentation.dto.SyncRequest
import com.circaso.users.presentation.dto.UpdateProfileRequest
import com.circaso.users.presentation.dto.UploadUrlResponse
import java.util.UUID

private fun malformed(field: String, why: String = "is not valid") = Resource.error(SharedError.Malformed(mapOf(field to why)))

// ---- request → command: shape checks live here and answer `request.malformed` ----

fun UpdateProfileRequest.toChange(): Resource<ProfileChange> {
    val avatar = privacy?.avatar?.let { raw -> AvatarVisibility.entries.firstOrNull { it.name == raw } ?: return malformed("privacy.avatar") }
    val about = privacy?.about?.let { raw -> AboutVisibility.entries.firstOrNull { it.name == raw } ?: return malformed("privacy.about") }
    return Resource.success(ProfileChange(displayName, this.about, false, avatar, about, privacy?.handleLookup, version))
}

fun SyncRequest.toCommand(userId: UserId): Resource<SyncCommand> {
    val id = runCatching { UUID.fromString(syncId) }.getOrNull() ?: return malformed("syncId")
    val numbers = phones.map { PhoneNumber.parse(it) ?: return malformed("phones", "contains a number that is not E.164") }.toSet()
    return Resource.success(SyncCommand(userId, id, batch, final, numbers))
}

fun ConnectRequest.toVia(): Resource<ConnectVia> =
    ConnectVia.entries.firstOrNull { it.name.equals(via, ignoreCase = true) }?.let { Resource.success(it) } ?: malformed("via")

fun parseUserId(raw: String?): UserId? = raw?.let(UserId::parse)

// ---- result → response ----

fun PublicProfile.toDto() = PublicProfileDto(userId.toString(), displayName, handle, avatarUrl, about)

fun MyProfile.toResponse() = MyProfileResponse(
    profile.userId.toString(), profile.displayName, profile.about, profile.handle, handleChangeableAt, avatarUrl,
    PrivacyDto(profile.privacy.avatar.name, profile.privacy.about.name, profile.privacy.handleLookup), profile.version,
)

fun UploadTarget.toResponse() = UploadUrlResponse(uploadUrl, objectKey, expiresInSeconds)

fun HandleCheck.toResponse() = HandleAvailabilityResponse(handle, availability == HandleAvailability.AVAILABLE, availability.name, suggestion)

fun SyncMatch.toDto() = SyncMatchDto(phone.e164, user.toDto())

fun ContactPage.toResponse() = ContactsResponse(
    entries.map { ContactDto(it.profile.toDto(), it.contact.source.name, it.contact.addedAt) }, nextCursor,
)

fun ContactPage.toAgentResponse() = AgentContactsResponse(
    entries.map { AgentContactDto(it.profile.userId.toString(), it.profile.displayName, it.profile.handle) }, nextCursor,
)

fun List<PublicProfile>.toAgentContacts() = AgentContactsResponse(map { AgentContactDto(it.userId.toString(), it.displayName, it.handle) })

fun Connection.toResponse() = ConnectResponse(user.toDto(), created)

fun InviteLink.toResponse() = InviteResponse(token, url, qrPayload, expiresAt)

fun List<PublicProfile>.toBlocks() = BlocksResponse(map { it.toDto() })
