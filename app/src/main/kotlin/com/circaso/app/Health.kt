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
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/** `ready` or `not_ready` (`up` on `/health/live`), with each connection's own result (decision 2026-10-27: a list). */
@Serializable
data class HealthReport(val status: String, val checks: List<HealthCheckStatus> = emptyList())

/**
 * One connection's result: [name] is `postgres`, `redis`, `streams` or `warm-up` (the start-up warm-up, `down` until it
 * has finished), [status] is `up` or `down`.
 */
@Serializable
data class HealthCheckStatus(val name: String, val status: String)

private val log = LoggerFactory.getLogger("com.circaso.app.Health")
private const val TAG = "Health"

/** The readiness report's entry for the start-up warm-up. */
const val WARM_UP_CHECK = "warm-up"

/**
 * `/health/live`: the process is up; restart it if this fails.
 * `/health/ready`: every connection answers and the start-up warm-up has finished ([readiness]; null means there is
 * no warm-up to wait for); take it out of the load balancer if this fails.
 * Neither is logged by the request log (the gateway's `RequestLog` skips `/health/…`, which is probed every few seconds).
 */
fun Route.healthRoutes(checks: List<HealthCheck>, readiness: Readiness? = null) {
    val transitions = HealthTransitions()
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
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Resource.Error(SharedError.Internal, e)
                        }
                    transitions.record(check.name, outcome)
                    HealthCheckStatus(check.name, if (outcome is Resource.Success) "up" else "down")
                } + listOfNotNull(readiness?.let { HealthCheckStatus(WARM_UP_CHECK, if (it.isReady) "up" else "down") })
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
                "`200` with `status: ready` when PostgreSQL, Redis and the event streams all answer and the " +
                    "start-up warm-up (`warm-up`) has finished; `503` with " +
                    "`status: not_ready` and the failing check marked `down` otherwise. " +
                    "Load balancers take the instance out of rotation when this fails. No token needed.",
        )
    }
}

/**
 * Logs a check only when its state changes: one WARN line when it goes down (with the exception's class, never its
 * message or stack, which can carry a host or a value), one INFO line when it comes back. A load balancer probes every
 * few seconds, so logging every failed probe would bury everything else while a dependency is down.
 */
internal class HealthTransitions {
    private val up = ConcurrentHashMap<String, Boolean>()

    fun record(name: String, outcome: Resource<Unit>) {
        val isUp = outcome is Resource.Success
        val was = up.put(name, isUp)
        if (was == isUp || (was == null && isUp)) return
        if (isUp) {
            log.info("Health check '$name' is up again")
        } else {
            val reason = (outcome as Resource.Error).cause?.let { it::class.simpleName } ?: outcome.error.code
            log.warn("Health check '$name' is down ($reason)")
        }
    }
}
