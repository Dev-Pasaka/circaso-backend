package com.circaso.gateway

import com.circaso.gateway.realtime.GatewayRealtime
import com.circaso.gateway.realtime.SocketHub
import com.circaso.gateway.realtime.gatewayIdentitySubscription
import com.circaso.identity.api.PushRegistrations
import com.circaso.identity.api.TokenVerifier
import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.RedisScopes
import com.circaso.integrations.api.PushGateway
import com.circaso.integrations.api.WebhookVendor
import com.circaso.shared.EventSubscription
import com.circaso.shared.Realtime
import com.circaso.shared.RealtimeModule
import com.circaso.web.RouteContributor
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.dsl.module
import org.koin.ktor.ext.getKoin
import kotlin.time.Duration.Companion.seconds

/**
 * The gateway's Koin bindings: the socket hub, `Realtime` for every module to call, and the gateway's consumer
 * group on `events:identity`. Needs `TokenVerifier` and `PushRegistrations` (identity) and `PushGateway`.
 */
fun gatewayModule(config: Config) =
    module {
        single { GatewayConfig.from(config) }
        single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
        single {
            val koin = this
            SocketHub(get<RedisScopes>().scope("rt"), get<TokenVerifier>(), { koin.getAll<RealtimeModule>() }, get())
        }
        single<Realtime> { GatewayRealtime(get(), get<PushGateway>(), get<PushRegistrations>()) }
        single<EventSubscription>(
            org.koin.core.qualifier
                .named("gateway.identity"),
        ) { gatewayIdentitySubscription(get()) }
    }

/**
 * Installs the edge: the gateway steps and request log on every call, then `/v1` with every module's routes,
 * the webhook receiver and the WebSocket hub.
 */
fun Application.installGateway() {
    val koin = getKoin()
    val deps = GatewayDeps(koin.get(), koin.get<TokenVerifier>(), koin.get<RedisScopes>())
    val hub = koin.get<SocketHub>()

    // Text, scripts and JSON are sent gzipped: the API reference's script is megabytes of JavaScript.
    install(Compression) {
        gzip { priority = 1.0 }
        deflate { priority = 0.5 }
        minimumSize(1_024)
    }
    install(RequestLog)
    installGatewayEdge(deps)
    install(WebSockets) { pingPeriod = 25.seconds }

    hub.start()
    monitor.subscribe(ApplicationStopping) { koin.get<CoroutineScope>().cancel() }

    routing {
        route("/v1") {
            koin.getAll<RouteContributor>().forEach { it.install(this) }
            webhookRoutes(koin.getAll<WebhookVendor>(), deps.config, deps.redis.scope("gw"), koin.get())
            hub.routes(this)
        }
        apiDocsRoutes(this@installGateway, deps.config.apiDocs)
    }
}
