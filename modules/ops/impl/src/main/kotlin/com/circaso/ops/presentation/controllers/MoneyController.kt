package com.circaso.ops.presentation.controllers

import com.circaso.ops.domain.models.Permission
import com.circaso.ops.domain.support.OpsSettings
import com.circaso.ops.presentation.dto.FinanceResponse
import com.circaso.ops.presentation.dto.TransferDto
import com.circaso.ops.presentation.dto.TransferPageResponse
import com.circaso.ops.presentation.mappers.FORBIDDEN
import com.circaso.ops.presentation.mappers.MALFORMED
import com.circaso.ops.presentation.mappers.PAGED
import com.circaso.ops.presentation.mappers.TAG_MONEY
import com.circaso.ops.presentation.mappers.pageLimit
import com.circaso.ops.presentation.mappers.parseEnum
import com.circaso.ops.presentation.mappers.parseInstant
import com.circaso.ops.presentation.mappers.parseTransferId
import com.circaso.ops.presentation.mappers.parseUserId
import com.circaso.ops.presentation.mappers.query
import com.circaso.ops.presentation.mappers.roles
import com.circaso.ops.presentation.mappers.toDto
import com.circaso.ops.presentation.mappers.toResponse
import com.circaso.ops.presentation.services.MoneyService
import com.circaso.shared.Resource
import com.circaso.shared.flatMap
import com.circaso.shared.map
import com.circaso.wallet.api.ReportBucket
import com.circaso.wallet.api.TransferFilter
import com.circaso.web.NoBody
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.staffOnly
import com.circaso.web.toDefaultResponse
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.time.Duration
import java.time.Instant

private const val DEFAULT_RANGE_DAYS = 30L

/** Every transaction and the finance report: FINANCE and ADMIN, read-only. */
fun Route.moneyController(money: MoneyService, settings: OpsSettings) {
    route("/ops/transactions") {
        staffOnly(Permission.TRANSACTIONS_READ.roles) {
            get {
                val result =
                    call.transferFilter().flatMap { filter ->
                        call.pageLimit(settings).flatMap { money.transactions(filter, call.query("cursor"), it) }
                    }
                call.respondDefault(
                    result.toDefaultResponse { page ->
                        TransferPageResponse(page.items.map { it.toDto() }, page.nextCursor)
                    },
                )
            }.docs<NoBody, TransferPageResponse>(
                "OPS · Every transaction",
                TAG_MONEY,
                listOf(MALFORMED, FORBIDDEN),
                notes =
                    "${roles("FINANCE", "ADMIN")} Newest first. Filters: `state`, `blockchain`, `asset`, `userId` " +
                        "(sender or recipient), `from`, `to` (dates or instants; `to` inclusive for a date). $PAGED",
            )

            get("{transferId}") {
                val result = parseTransferId(call.parameters["transferId"]).flatMap { money.transfer(it) }
                call.respondDefault(result.toDefaultResponse { it.toDto() })
            }.docs<NoBody, TransferDto>(
                "OPS · One transaction",
                TAG_MONEY,
                listOf("ops.transfer_not_found", MALFORMED, FORBIDDEN),
                roles("FINANCE", "ADMIN"),
            )
        }
    }

    route("/ops/finance") {
        staffOnly(Permission.FINANCE_READ.roles) {
            get {
                val result = call.financeQuery()
                val response =
                    result.flatMap { (from, to, bucket) ->
                        money
                            .finance(
                                from,
                                to,
                                bucket,
                            ).map { it.report.toResponse(from, to, bucket.name, it.groupWallets) }
                    }
                call.respondDefault(response.toDefaultResponse { it })
            }.docs<NoBody, FinanceResponse>(
                "OPS · Fees and volumes over time",
                TAG_MONEY,
                listOf(MALFORMED, FORBIDDEN),
                notes =
                    "${roles("FINANCE", "ADMIN")} `from`, `to` (dates or instants; default the last 30 days), " +
                        "`bucket` = day | week | month. Confirmed transfers only; fees are the collected fee legs.",
            )
        }
    }
}

private fun ApplicationCall.transferFilter(): Resource<TransferFilter> =
    parseInstant(query("from"), "from").flatMap { from ->
        parseInstant(query("to"), "to", endOfDay = true).flatMap { to ->
            val user = query("userId")?.let { raw -> parseUserId(raw).map { it } } ?: Resource.success(null)
            user.map { userId ->
                TransferFilter(
                    state = query("state")?.uppercase(),
                    blockchain = query("blockchain")?.uppercase(),
                    asset = query("asset")?.uppercase(),
                    userId = userId,
                    from = from,
                    to = to,
                )
            }
        }
    }

private fun ApplicationCall.financeQuery(): Resource<Triple<Instant, Instant, ReportBucket>> =
    parseInstant(query("to"), "to", endOfDay = true).flatMap { to ->
        parseInstant(query("from"), "from").flatMap { from ->
            parseEnum<ReportBucket>(query("bucket"), "bucket").map { bucket ->
                val end = to ?: Instant.now()
                Triple(from ?: end.minus(Duration.ofDays(DEFAULT_RANGE_DAYS)), end, bucket ?: ReportBucket.DAY)
            }
        }
    }
