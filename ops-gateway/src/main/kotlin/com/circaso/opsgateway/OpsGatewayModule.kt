package com.circaso.opsgateway

import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.HttpClients
import com.circaso.infrastructure.api.RedisScopes
import com.circaso.infrastructure.api.Secrets
import com.circaso.ops.api.StaffDirectory
import com.circaso.opsgateway.auth.AccessVerifier
import com.circaso.opsgateway.auth.DevLogin
import com.circaso.opsgateway.auth.HttpAccessKeys
import com.circaso.web.OpsRouteContributor
import io.ktor.server.application.Application
import org.koin.dsl.module
import org.koin.ktor.ext.getKoin

/**
 * The ops gateway's Koin bindings. Reading [OpsGatewayConfig] here is what stops the process from starting with the dev
 * login in production, or in production without Cloudflare Access.
 */
fun opsGatewayModule(config: Config, secrets: Secrets) =
    module {
        single { OpsGatewayConfig.from(config, secrets) }
        single {
            val settings = get<OpsGatewayConfig>()
            OpsGatewayDeps(
                config = settings,
                access = settings.access?.let { AccessVerifier(it, HttpAccessKeys(get<HttpClients>(), it)) },
                devLogin = settings.devLoginSecret?.let { DevLogin(it, settings.devLoginTtl) },
                directory = get<StaffDirectory>(),
                redis = get<RedisScopes>(),
            )
        }
    }

/** Installs the staff edge with every staff route the modules contributed. */
fun Application.installOpsGateway() {
    val koin = getKoin()
    installOpsGateway(koin.get<OpsGatewayDeps>(), koin.getAll<OpsRouteContributor>())
}
