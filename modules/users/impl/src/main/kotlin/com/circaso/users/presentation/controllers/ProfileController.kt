package com.circaso.users.presentation.controllers

import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.SharedError
import com.circaso.shared.flatMap
import com.circaso.users.presentation.dto.MyProfileResponse
import com.circaso.users.presentation.dto.PublicProfileDto
import com.circaso.users.presentation.dto.SetAvatarRequest
import com.circaso.users.presentation.dto.UpdateProfileRequest
import com.circaso.users.presentation.dto.UploadUrlRequest
import com.circaso.users.presentation.dto.UploadUrlResponse
import com.circaso.users.presentation.mappers.parseUserId
import com.circaso.users.presentation.mappers.toChange
import com.circaso.users.presentation.mappers.toDto
import com.circaso.users.presentation.mappers.toResponse
import com.circaso.users.presentation.services.ProfileService
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import com.circaso.web.userId
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post

private const val TAG = "Profile"
private const val SCOPE = "Scope: `app`."

/** Your own profile and picture, and other people's public profiles. U1 to U4 and U10. */
fun Route.profileController(profiles: ProfileService) {
    authenticate(Scope.APP) {
        route("/me") {
            route("/profile") {
                get {
                    call.respondDefault(profiles.getMine(call.userId()).toDefaultResponse { it.toResponse() })
                }.docs<NoBody, MyProfileResponse>("U1 · Your full profile, privacy settings included", TAG, notes = SCOPE)

                patch {
                    val result = call.receive<UpdateProfileRequest>().toChange().flatMap { profiles.update(call.userId(), it) }
                    call.respondDefault(result.toDefaultResponse { it.toResponse() })
                }.docs<UpdateProfileRequest, MyProfileResponse>(
                    "U2 · Change name, about and privacy", TAG, listOf("users.profile_invalid", "users.version_conflict"),
                    notes = "$SCOPE Send only the fields being changed, plus the `version` you last read. An `about` of `\"\"` clears it.",
                )
            }

            route("/avatar") {
                post("upload-url") {
                    val body = call.receive<UploadUrlRequest>()
                    call.respondDefault(profiles.prepareAvatar(call.userId(), body.contentType, body.sizeBytes).toDefaultResponse { it.toResponse() })
                }.docs<UploadUrlRequest, UploadUrlResponse>("U3 · A pre-signed URL to upload a JPEG, PNG or WebP up to 5 MB", TAG, listOf("users.avatar_invalid"), notes = SCOPE)

                post {
                    call.respondDefault(profiles.setAvatar(call.userId(), call.receive<SetAvatarRequest>().objectKey).toDefaultResponse { it.toResponse() })
                }.docs<SetAvatarRequest, MyProfileResponse>("U4 · Confirm an uploaded picture", TAG, listOf("users.avatar_invalid"), notes = SCOPE)

                delete {
                    call.respondDefault(profiles.removeAvatar(call.userId()).toDefaultResponse { it.toResponse() })
                }.docs<NoBody, MyProfileResponse>("U4 · Remove your picture", TAG, notes = SCOPE)
            }
        }

        route("/users") {
            get("{userId}") {
                val target = parseUserId(call.parameters["userId"])
                val result: Resource<com.circaso.users.api.PublicProfile> =
                    if (target == null) Resource.error(SharedError.Malformed(mapOf("userId" to "is not valid"))) else profiles.getPublic(call.userId(), target)
                call.respondDefault(result.toDefaultResponse { it.toDto() })
            }.docs<NoBody, PublicProfileDto>("U10 · The public profile of someone you can reach", TAG, listOf("users.not_reachable"), notes = SCOPE)
        }
    }
}
