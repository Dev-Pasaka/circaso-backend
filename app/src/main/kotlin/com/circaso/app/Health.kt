package com.circaso.app

import com.circaso.infrastructure.api.HealthCheck
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.web.NoBody
import com.circaso.web.docs
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/** `ready` or `not_ready` (`up` on `/health/live`), with each connection's own result (decision 2026-10-27: a list). */
@Serializable
data class HealthReport(val status: String, val checks: List<HealthCheckStatus> = emptyList())

/** One connection's result: [name] is `redis`, `scylla` or `streams`, [status] is `up` or `down`. */
@Serializable
data class HealthCheckStatus(val name: String, val status: String)

private val log = LoggerFactory.getLogger("com.circaso.app.Health")
private const val TAG = "Health"

/**
 * `/health/live`: the process is up; restart it if this fails.
 * `/health/ready`: every connection answers; take it out of the load balancer if this fails.
 * Neither is logged by the request log; metrics count them.
 */
fun Route.healthRoutes(checks: List<HealthCheck>) {
    route("/health") {
        get("live") {
            call.respond(HttpStatusCode.OK, HealthReport("up"))
        }.docs<NoBody, HealthReport>(
            "Is the process up?",
            TAG,
            envelope = false,
            notes =
                "Answers `200` with `{\"status\":\"up\"}` whatever the connections are doing. Orchestrators " +
                    "restart the process when this fails. No token needed.",
        )
        get("ready") {
            val report =
                checks.map { check ->
                    val outcome =
                        try {
                            check.check()
                        } catch (e: Exception) {
                            log.warn("Health check '${check.name}' threw", e)
                            Resource.Error(SharedError.Internal, e)
                        }
                    HealthCheckStatus(check.name, if (outcome is Resource.Success) "up" else "down")
                }
            val ready = report.all { it.status == "up" }
            call.respond(
                if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                HealthReport(if (ready) "ready" else "not_ready", report),
            )
        }.docs<NoBody, HealthReport>(
            "Is every connection working?",
            TAG,
            envelope = false,
            notes =
                "`200` with `status: ready` when Redis, ScyllaDB and the event streams all answer; `503` with " +
                    "`status: not_ready` and the failing check marked `down` otherwise. " +
                    "Load balancers take the instance out of rotation when this fails. No token needed.",
        )
    }
}
