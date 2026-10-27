package com.circaso.assistant.data.repositories

import com.circaso.assistant.data.tables.EntitlementsTable
import com.circaso.assistant.domain.models.Plan
import com.circaso.assistant.domain.repositories.EntitlementRepository
import com.circaso.infrastructure.api.Database
import com.circaso.shared.UserId
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.select
import java.time.Clock

/**
 * The plan a turn is checked against. A paid plan counts until its `plan_ends_at`, compared with the injected [clock]
 * so tests can move time; no row, an ended plan or an unknown name is the free plan.
 */
class EntitlementRepositoryImpl(private val database: Database, private val clock: Clock) : EntitlementRepository {
    override suspend fun planOf(userId: UserId): Plan {
        val now = clock.instant()
        val plan =
            database.tx("entitlements.plan-of") {
                EntitlementsTable
                    .select(EntitlementsTable.plan)
                    .where {
                        (EntitlementsTable.userId eq userId.value) and
                            (EntitlementsTable.planEndsAt.isNull() or (EntitlementsTable.planEndsAt greater now))
                    }.singleOrNull()
                    ?.get(EntitlementsTable.plan)
            } ?: return Plan.FREE
        return Plan.entries.firstOrNull { it.name == plan } ?: Plan.FREE
    }
}
