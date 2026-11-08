package com.circaso.ops.presentation.controllers

import com.circaso.ops.domain.models.Permission
import com.circaso.ops.domain.models.WalletFilter
import com.circaso.ops.domain.support.OpsSettings
import com.circaso.ops.presentation.dto.GroupDetailResponse
import com.circaso.ops.presentation.dto.GroupPageResponse
import com.circaso.ops.presentation.mappers.MALFORMED
import com.circaso.ops.presentation.mappers.PAGED
import com.circaso.ops.presentation.mappers.TAG_GROUPS
import com.circaso.ops.presentation.mappers.pageLimit
import com.circaso.ops.presentation.mappers.parseChatId
import com.circaso.ops.presentation.mappers.parseEnum
import com.circaso.ops.presentation.mappers.query
import com.circaso.ops.presentation.mappers.roles
import com.circaso.ops.presentation.mappers.toDto
import com.circaso.ops.presentation.mappers.toResponse
import com.circaso.ops.presentation.services.GroupService
import com.circaso.shared.flatMap
import com.circaso.web.NoBody
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.staff
import com.circaso.web.staffOnly
import com.circaso.web.toDefaultResponse
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.groupController(groups: GroupService, settings: OpsSettings) {
    route("/ops/groups") {
        staffOnly(Permission.GROUPS_READ.roles) {
            get {
                val result =
                    parseEnum<WalletFilter>(call.query("wallet"), "wallet").flatMap { filter ->
                        call.pageLimit(settings).flatMap {
                            groups.list(filter ?: WalletFilter.ANY, call.query("cursor"), it)
                        }
                    }
                call.respondDefault(
                    result.toDefaultResponse { page ->
                        GroupPageResponse(page.items.map { it.toDto() }, page.nextCursor)
                    },
                )
            }.docs<NoBody, GroupPageResponse>(
                "OPS · Groups",
                TAG_GROUPS,
                listOf(MALFORMED),
                notes =
                    "${roles("any")} Newest first. `wallet` = any | with | without. Group names are end-to-end " +
                        "encrypted: never on the server. $PAGED",
            )

            get("{chatId}") {
                val withMoney = Permission.GROUPS_MONEY.allows(call.staff().role)
                val result = parseChatId(call.parameters["chatId"]).flatMap { groups.get(it, withMoney) }
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<NoBody, GroupDetailResponse>(
                "OPS · One group",
                TAG_GROUPS,
                listOf("ops.group_not_found", MALFORMED),
                notes = "${roles("any")} The wallet's balances only for FINANCE and ADMIN.",
            )
        }
    }
}
