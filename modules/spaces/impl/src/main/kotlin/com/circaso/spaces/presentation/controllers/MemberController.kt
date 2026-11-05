package com.circaso.spaces.presentation.controllers

import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.UserId
import com.circaso.shared.flatMap
import com.circaso.spaces.api.SpaceId
import com.circaso.spaces.domain.models.MemberFilter
import com.circaso.spaces.domain.models.MembersQuery
import com.circaso.spaces.domain.models.MuteDuration
import com.circaso.spaces.domain.models.Role
import com.circaso.spaces.presentation.dto.BannedResponse
import com.circaso.spaces.presentation.dto.MemberDto
import com.circaso.spaces.presentation.dto.MembersResponse
import com.circaso.spaces.presentation.dto.MuteRequest
import com.circaso.spaces.presentation.dto.RemovedResponse
import com.circaso.spaces.presentation.dto.RoleRequest
import com.circaso.spaces.presentation.mappers.parseEnum
import com.circaso.spaces.presentation.mappers.parseLimit
import com.circaso.spaces.presentation.mappers.parseSpaceId
import com.circaso.spaces.presentation.mappers.parseUserId
import com.circaso.spaces.presentation.mappers.toDto
import com.circaso.spaces.presentation.mappers.toResponse
import com.circaso.spaces.presentation.services.MemberService
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import com.circaso.web.userId
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route

private const val CANNOT = "spaces.cannot_moderate"

/** Members, roles and moderation (Spaces API, SP23-SP27). */
fun Route.memberController(members: MemberService, pageDefault: Int, pageMax: Int) {
    route("/spaces/{spaceId}") {
        authenticate(Scope.APP) {
            get("members") {
                val query = call.request.queryParameters
                val filter = query["filter"]?.let { parseEnum(it, "filter", MemberFilter.entries.toTypedArray()) } ?: Resource.success(MemberFilter.ALL)
                val result =
                    parseSpaceId(call.parameters["spaceId"]).flatMap { space ->
                        filter.flatMap { f ->
                            parseLimit(query["limit"], pageDefault, pageMax).flatMap { limit ->
                                members.members(MembersQuery(call.userId(), space, f, query["q"], query["cursor"], limit))
                            }
                        }
                    }
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<NoBody, MembersResponse>(
                "SP23 · Who is in a space",
                TAG,
                listOf("spaces.members_only", "spaces.banned", "spaces.not_admin", NOT_FOUND, MALFORMED),
                notes =
                    "$SCOPE Everyone who can see inside: `team`, `contacts`, `count`. Moderators and up also get " +
                        "`members` (newest first), with `filter` `ALL`, `MUTED` or `BANNED` (admins) and `q`.",
            )

            route("/members/{userId}") {
                put("role") {
                    val request = call.receive<RoleRequest>()
                    val result =
                        target(call.parameters["spaceId"], call.parameters["userId"]) { space, user ->
                            parseEnum(request.role, "role", Role.entries.toTypedArray()).flatMap { members.role(call.userId(), space, user, it) }
                        }
                    call.respondDefault(result.toDefaultResponse { it.toDto() })
                }.docs<RoleRequest, MemberDto>(
                    "SP24 · Change someone's role",
                    TAG,
                    listOf("spaces.not_admin", "spaces.not_owner", CANNOT, "spaces.not_member", MALFORMED),
                    notes = "$SCOPE $IDEMPOTENT `ADMIN` (the owner only), `MODERATOR` or `MEMBER`.",
                )

                put("mute") {
                    val request = call.receive<MuteRequest>()
                    val result =
                        target(call.parameters["spaceId"], call.parameters["userId"]) { space, user ->
                            parseEnum(request.duration, "duration", MuteDuration.entries.toTypedArray()).flatMap {
                                members.mute(call.userId(), space, user, it)
                            }
                        }
                    call.respondDefault(result.toDefaultResponse { it.toDto() })
                }.docs<MuteRequest, MemberDto>(
                    "SP25 · Mute someone",
                    TAG,
                    listOf("spaces.not_moderator", CANNOT, "spaces.not_member", MALFORMED),
                    notes = "$SCOPE $IDEMPOTENT `HOUR`, `DAY`, `WEEK` or `UNTIL_UNMUTED`.",
                )

                delete("mute") {
                    val result = target(call.parameters["spaceId"], call.parameters["userId"]) { space, user -> members.mute(call.userId(), space, user, null) }
                    call.respondDefault(result.toDefaultResponse { it.toDto() })
                }.docs<NoBody, MemberDto>("SP25 · Unmute someone", TAG, listOf("spaces.not_moderator", CANNOT, "spaces.not_member", MALFORMED))

                delete {
                    val result = target(call.parameters["spaceId"], call.parameters["userId"]) { space, user -> members.remove(call.userId(), space, user) }
                    call.respondDefault(result.toDefaultResponse { RemovedResponse(true) })
                }.docs<NoBody, RemovedResponse>(
                    "SP26 · Remove someone from the space",
                    TAG,
                    listOf("spaces.not_admin", CANNOT, "spaces.not_member", MALFORMED),
                    notes = "$SCOPE $IDEMPOTENT They may follow again; their posts stay; a paid period isn't refunded.",
                )
            }

            put("bans/{userId}") {
                val result = target(call.parameters["spaceId"], call.parameters["userId"]) { space, user -> members.ban(call.userId(), space, user, true) }
                call.respondDefault(result.toDefaultResponse { BannedResponse(it) })
            }.docs<NoBody, BannedResponse>(
                "SP27 · Ban someone",
                TAG,
                listOf("spaces.not_admin", CANNOT, MALFORMED),
                notes = "$SCOPE $IDEMPOTENT Stops follow, join, requests, invites, paying and the private chat with the owner.",
            )

            delete("bans/{userId}") {
                val result = target(call.parameters["spaceId"], call.parameters["userId"]) { space, user -> members.ban(call.userId(), space, user, false) }
                call.respondDefault(result.toDefaultResponse { BannedResponse(it) })
            }.docs<NoBody, BannedResponse>("SP27 · Lift a ban", TAG, listOf("spaces.not_admin", CANNOT, MALFORMED))
        }
    }
}

private suspend fun <T> target(
    space: String?,
    user: String?,
    block: suspend (SpaceId, UserId) -> Resource<T>,
): Resource<T> = parseSpaceId(space).flatMap { s -> parseUserId(user).flatMap { u -> block(s, u) } }
