package com.circaso.identity.domain.usecases

import com.circaso.identity.api.Principal
import com.circaso.identity.domain.models.SecurityPage
import com.circaso.identity.domain.repositories.SecurityLogRepository
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import java.util.UUID

/** I13: the security log, newest first, paged by an opaque cursor. */
class ListSecurityEvents(private val log: SecurityLogRepository) {
    suspend operator fun invoke(principal: Principal, cursor: String?, limit: Int): Resource<SecurityPage> {
        val before = cursor?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: return Resource.error(SharedError.Malformed(mapOf("cursor" to "invalid"))) }
        val page = limit.coerceIn(1, 200)
        val events = log.list(principal.userId, before, page + 1)
        val shown = events.take(page)
        return Resource.success(SecurityPage(shown, if (events.size > page) shown.last().eventId?.toString() else null))
    }
}
