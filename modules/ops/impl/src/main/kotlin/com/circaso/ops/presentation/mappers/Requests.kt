package com.circaso.ops.presentation.mappers

import com.circaso.messaging.api.ChatId
import com.circaso.ops.domain.models.Actor
import com.circaso.ops.domain.support.OpsSettings
import com.circaso.ops.domain.support.limit
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.StaffRole
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import com.circaso.support.api.TicketId
import com.circaso.wallet.api.TransferId
import com.circaso.web.staff
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.callid.callId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.UUID

// Shape checks for the staff routes: path ids, page sizes, dates. A value that isn't one answers 400 request.malformed
// naming the field, before anything is read.

/** The verified staff member, as the use cases take them, with the request id for the audit line. */
fun ApplicationCall.actor(): Actor {
    val staff = staff()
    return Actor(staff.staffId, staff.email, staff.role, callId)
}

fun ApplicationCall.query(name: String): String? = request.queryParameters[name]?.trim()?.takeIf { it.isNotEmpty() }

fun ApplicationCall.pageLimit(settings: OpsSettings): Resource<Int> {
    val raw = query("limit") ?: return Resource.success(settings.limit(null))
    val asked = raw.toIntOrNull()?.takeIf { it > 0 } ?: return malformed("limit", "must be a positive number")
    return Resource.success(settings.limit(asked))
}

fun parseUserId(raw: String?): Resource<UserId> =
    raw?.let(UserId::parse)?.let { Resource.success(it) } ?: malformed("userId", "must be a user id")

fun parseChatId(raw: String?): Resource<ChatId> =
    raw?.let(Uuid7::parse)?.let { Resource.success(ChatId(it)) } ?: malformed("chatId", "must be a chat id")

fun parseTicketId(raw: String?): Resource<TicketId> =
    TicketId.parse(raw)?.let { Resource.success(it) } ?: malformed("ticketId", "must be a ticket id")

fun parseTransferId(raw: String?): Resource<TransferId> =
    raw?.let(Uuid7::parse)?.let { Resource.success(TransferId(it)) } ?: malformed("transferId", "must be an id")

fun parseUuid(raw: String?, field: String): Resource<UUID> =
    raw?.let(Uuid7::parse)?.let { Resource.success(it) } ?: malformed(field, "must be an id")

fun parseRole(raw: String?, field: String = "role"): Resource<StaffRole> =
    raw?.let(StaffRole::parse)?.let { Resource.success(it) } ?: malformed(field, "must be ADMIN, SUPPORT or FINANCE")

/** An ISO date (`2026-11-01`, the start of that UTC day) or an instant; null when absent. */
fun parseInstant(
    raw: String?,
    field: String,
    endOfDay: Boolean = false,
): Resource<Instant?> {
    if (raw == null) return Resource.success(null)
    return try {
        Resource.success(Instant.parse(raw))
    } catch (_: DateTimeParseException) {
        try {
            val day = LocalDate.parse(raw)
            val start = (if (endOfDay) day.plusDays(1) else day).atStartOfDay().toInstant(ZoneOffset.UTC)
            Resource.success(start)
        } catch (_: DateTimeParseException) {
            malformed(field, "must be a date (2026-11-01) or an instant")
        }
    }
}

inline fun <reified E : Enum<E>> parseEnum(raw: String?, field: String): Resource<E?> {
    if (raw == null) return Resource.success(null)
    val value = enumValues<E>().firstOrNull { it.name.equals(raw, ignoreCase = true) }
    return value?.let { Resource.success(it) }
        ?: malformed(field, "must be one of ${enumValues<E>().joinToString { it.name }}")
}

fun malformed(field: String, problem: String): Resource<Nothing> = Resource.error(SharedError.Malformed(field, problem))
