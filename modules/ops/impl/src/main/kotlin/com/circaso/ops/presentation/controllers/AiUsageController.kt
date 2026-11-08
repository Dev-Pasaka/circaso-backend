package com.circaso.ops.presentation.controllers

import com.circaso.ops.domain.models.Permission
import com.circaso.ops.presentation.dto.AiUsageResponse
import com.circaso.ops.presentation.mappers.FORBIDDEN
import com.circaso.ops.presentation.mappers.MALFORMED
import com.circaso.ops.presentation.mappers.TAG_MONEY
import com.circaso.ops.presentation.mappers.aiUsageQuery
import com.circaso.ops.presentation.mappers.roles
import com.circaso.ops.presentation.mappers.toResponse
import com.circaso.ops.presentation.services.AiUsageService
import com.circaso.shared.flatMap
import com.circaso.web.NoBody
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.staffOnly
import com.circaso.web.toDefaultResponse
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/** What Asha's use of Claude costs (decision 2026-11-08): FINANCE and ADMIN, read-only. */
fun Route.aiUsageController(usage: AiUsageService) {
    route("/ops/ai-usage") {
        staffOnly(Permission.AI_USAGE_READ.roles) {
            get {
                val result =
                    call.aiUsageQuery().flatMap { q -> usage.report(q.from, q.until, q.bucket, q.limit) }
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<NoBody, AiUsageResponse>(
                "OPS · AI usage and cost",
                TAG_MONEY,
                listOf(MALFORMED, FORBIDDEN),
                notes =
                    "${roles("FINANCE", "ADMIN")} What Asha's use of Claude cost over whole UTC days: `from`, `to` " +
                        "(dates, `to` inclusive, or instants; default the last 30 days with today), `bucket` = day | " +
                        "week | month, `limit` = how many of the costliest users (default 10, at most 50). Totals " +
                        "(turns, voice seconds, web searches, the four token kinds, active users, estimated cost and " +
                        "cost per active user), a series per period, a breakdown by model and the costliest users. " +
                        "The estimate prices the assistant's daily token totals with `prices` (configuration, " +
                        "`OPS_AI_*`); it assumes prompts under 100K tokens. `anthropic` is Anthropic's own bill for " +
                        "the same days when `OPS_ANTHROPIC_ADMIN_KEY` is set (`status` ok), `off` without it, and " +
                        "`unavailable` when Anthropic couldn't be read: the report never fails for that. Dollars are " +
                        "micro-dollars (1 USD = 1,000,000).",
            )
        }
    }
}
