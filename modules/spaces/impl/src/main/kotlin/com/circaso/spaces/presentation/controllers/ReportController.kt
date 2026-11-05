package com.circaso.spaces.presentation.controllers

import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.flatMap
import com.circaso.spaces.domain.models.MuteDuration
import com.circaso.spaces.domain.models.ReportAction
import com.circaso.spaces.presentation.dto.OpsReportsResponse
import com.circaso.spaces.presentation.dto.RemoveSpaceRequest
import com.circaso.spaces.presentation.dto.RemovedResponse
import com.circaso.spaces.presentation.dto.ReportRequest
import com.circaso.spaces.presentation.dto.ReportResponse
import com.circaso.spaces.presentation.dto.ReportsResponse
import com.circaso.spaces.presentation.dto.ResolveReportRequest
import com.circaso.spaces.presentation.dto.ResolvedResponse
import com.circaso.spaces.presentation.mappers.parseEnum
import com.circaso.spaces.presentation.mappers.parsePage
import com.circaso.spaces.presentation.mappers.parsePostId
import com.circaso.spaces.presentation.mappers.parseSpaceId
import com.circaso.spaces.presentation.mappers.toCommand
import com.circaso.spaces.presentation.mappers.toResponse
import com.circaso.spaces.presentation.services.ReportService
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import com.circaso.web.userId
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** Reports (Spaces API, SP35-SP37) and Circaso's queue (SPO1, SPO2, scope `ops`). */
fun Route.reportController(
    reports: ReportService,
    pageDefault: Int,
    pageMax: Int,
) {
    route("/spaces/{spaceId}/reports") {
        authenticate(Scope.APP) {
            post {
                val request = call.receive<ReportRequest>()
                val result =
                    parseSpaceId(call.parameters["spaceId"]).flatMap { space ->
                        request.toCommand(call.userId(), space).flatMap { reports.report(it) }
                    }
                call.respondDefault(result.toDefaultResponse { ReportResponse(it.reportId.toString()) })
            }.docs<ReportRequest, ReportResponse>(
                "SP35 · Report a post or a space",
                TAG,
                listOf(
                    "spaces.report_invalid",
                    "spaces.post_not_found",
                    "spaces.too_many_reports",
                    NOT_FOUND,
                    MALFORMED,
                ),
                notes =
                    "$SCOPE $IDEMPOTENT A post goes to the space's moderators and Circaso; the whole space (no " +
                        "`postId`) to Circaso only. The reporter is never named to the space.",
            )

            get {
                val open = call.request.queryParameters["state"]?.uppercase() != "DONE"
                val result = parseSpaceId(call.parameters["spaceId"]).flatMap { reports.list(call.userId(), it, open) }
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<NoBody, ReportsResponse>(
                "SP36 · Reports in a space (moderators)",
                TAG,
                listOf("spaces.not_moderator", NOT_FOUND, MALFORMED),
                notes = "$SCOPE `?state=OPEN` (most reported first) or `DONE` (newest first, with who acted).",
            )

            post("{postId}/resolve") {
                val request = call.receive<ResolveReportRequest>()
                val mute =
                    request.muteDuration?.let { parseEnum(it, "muteDuration", MuteDuration.entries.toTypedArray()) }
                        ?: Resource.success(null)
                val result =
                    parseSpaceId(call.parameters["spaceId"]).flatMap { space ->
                        parsePostId(call.parameters["postId"]).flatMap { post ->
                            parseEnum(request.action, "action", ReportAction.entries.toTypedArray()).flatMap { action ->
                                mute.flatMap { reports.resolve(call.userId(), space, post, action, it) }
                            }
                        }
                    }
                call.respondDefault(result.toDefaultResponse { ResolvedResponse(true) })
            }.docs<ResolveReportRequest, ResolvedResponse>(
                "SP37 · Act on a reported post (moderators)",
                TAG,
                listOf(
                    "spaces.not_moderator",
                    "spaces.not_admin",
                    "spaces.report_not_found",
                    "spaces.cannot_moderate",
                    MALFORMED,
                ),
                notes =
                    "$SCOPE $IDEMPOTENT `KEEP`, `REMOVE_POST`, `MUTE` (with `muteDuration`) or " +
                        "`REMOVE_MEMBER` (admins). Every open report on the post closes.",
            )
        }
    }

    route("/ops/spaces") {
        authenticate(Scope.OPS) {
            get("reports") {
                val query = call.request.queryParameters
                val result =
                    parsePage(query["cursor"], query["limit"], pageDefault, pageMax).flatMap { (offset, limit) ->
                        reports.opsList(offset, limit)
                    }
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<NoBody, OpsReportsResponse>(
                "SPO1 · Circaso's report queue",
                TAG,
                listOf(MALFORMED),
                notes = "Scope: `ops`. Newest first.",
            )

            post("{spaceId}/remove") {
                val request = call.receive<RemoveSpaceRequest>()
                val result =
                    parseSpaceId(
                        call.parameters["spaceId"],
                    ).flatMap { reports.removeSpace(it, request.reason) }
                call.respondDefault(result.toDefaultResponse { RemovedResponse(true) })
            }.docs<RemoveSpaceRequest, RemovedResponse>(
                "SPO2 · Remove a space for breaking the rules",
                TAG,
                listOf(NOT_FOUND, MALFORMED),
                notes =
                    "Scope: `ops`. $IDEMPOTENT Everyone in it gets `space.updated`; SP3 then answers " +
                        "`spaces.space_unavailable`.",
            )
        }
    }
}
