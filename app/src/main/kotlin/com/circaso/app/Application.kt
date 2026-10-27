package com.circaso.app

import com.circaso.gateway.gatewayModule
import com.circaso.gateway.installGateway
import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.api.HealthCheck
import com.circaso.infrastructure.api.Secrets
import com.circaso.infrastructure.impl.database.installPostgres
import com.circaso.infrastructure.impl.infrastructureModule
import com.circaso.infrastructure.impl.startInfrastructure
import com.circaso.integrations.impl.integrationsModule
import com.circaso.observability.impl.observabilityModule
import com.circaso.web.installJson
import com.circaso.web.installRequestId
import com.circaso.web.installStatusPages
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import org.koin.ktor.ext.getKoin
import org.koin.ktor.plugin.Koin

/**
 * The web side: request id, JSON, StatusPages and the health routes. The gateway's `/v1` routing (M3) and each
 * module's routes (M4, M5) are added here as they arrive.
 */
fun Application.circasoModule(
    config: AppConfig,
    healthChecks: List<HealthCheck> = emptyList(),
    devGoogleClientId: String? = null,
) {
    installRequestId()
    installJson()
    installStatusPages()
    routing {
        homeRoute()
        healthRoutes(healthChecks)
        if (config.environment.isLocal && devGoogleClientId != null) devSocialRoutes(devGoogleClientId)
    }
}

/**
 * Wires everything, in the order it depends on itself. Koin first (observability, infrastructure and the business
 * modules); then PostgreSQL (its pools, and a check that every module's schema is migrated), once, with what every module
 * contributed; then the running parts (event consumers,
 * the outbox relay, the scheduler); then the web side with every health check collected. [config] and [secrets] are
 * where settings come from; tests pass their own.
 */
fun Application.circasoServe(
    app: AppConfig,
    config: Config,
    secrets: Secrets,
) {
    install(Koin) {
        modules(
            observabilityModule,
            infrastructureModule(config, secrets),
            integrationsModule(config, secrets),
            gatewayModule(config),
        )
        modules(businessModules(config))
    }
    val koin = getKoin()
    installPostgres(koin.getAll<DatabaseModuleSpec>())
    startInfrastructure()
    val googleClient =
        config
            .section("integrations")
            .string("social.google.client-ids", "")
            .split(',')
            .map {
                it.trim()
            }.firstOrNull { it.isNotEmpty() }
    circasoModule(app, koin.getAll<HealthCheck>(), googleClient)
    installGateway()
}
