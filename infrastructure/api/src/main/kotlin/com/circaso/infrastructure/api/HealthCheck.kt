package com.circaso.infrastructure.api

import com.circaso.shared.Resource

/**
 * One connection or runtime whose health `/health/ready` reports: PostgreSQL, Redis, the stream consumers.
 * Infrastructure registers its own; the app collects them all.
 */
interface HealthCheck {
    /** Short and stable, for example `postgres`; it appears in the readiness report. */
    val name: String

    /** A `Resource.Error` means "not ready"; it must answer quickly and never throw. */
    suspend fun check(): Resource<Unit>
}
