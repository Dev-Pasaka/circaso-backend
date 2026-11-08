package com.circaso.opsgateway

import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.Environment
import com.circaso.infrastructure.api.Secrets
import java.net.URI
import java.time.Duration
import com.circaso.gateway.access.AccessSettings

/**
 * The ops gateway's settings (decision 2026-11-08), all `OPS_*`, none shared with the app gateway:
 *
 * - `OPS_ACCESS_TEAM_DOMAIN` (`circaso.cloudflareaccess.com`) and `OPS_ACCESS_AUD` (the Access application's audience
 *   tag): both set turns Cloudflare Access sign-in on. The certificates are read from
 *   `https://<team>/cdn-cgi/access/certs` and the issuer must be `https://<team>`.
 * - `OPS_ALLOWED_ORIGINS`: the portal's origins (`https://admin.circaso.com`), comma separated, exact. CORS answers only
 *   these, and the sign-in hand-off only returns to them.
 * - `OPS_DEV_LOGIN_SECRET` (a secret, at least 32 characters): turns the sandbox dev login on. Refused in production:
 *   the process will not start with it there.
 * - `OPS_DEV_LOGIN_TTL_MINUTES` (default 480), `OPS_RATE_LIMIT_PER_MINUTE` per staff member (default 300),
 *   `OPS_PUBLIC_RATE_LIMIT_PER_MINUTE` per address for the routes that need no sign-in (default 30).
 */
data class OpsGatewayConfig(
    val environment: Environment,
    val access: AccessSettings?,
    val allowedOrigins: Set<String>,
    val devLoginSecret: String?,
    val devLoginTtl: Duration = Duration.ofMinutes(DEFAULT_DEV_TTL_MINUTES),
    val staffPerMinute: Long = DEFAULT_STAFF_PER_MINUTE,
    val publicPerMinute: Long = DEFAULT_PUBLIC_PER_MINUTE,
) {
    val devLoginEnabled: Boolean get() = devLoginSecret != null

    init {
        require(environment != Environment.PRODUCTION || devLoginSecret == null) {
            "OPS_DEV_LOGIN_SECRET is set in production: the dev login is for the sandbox and local runs only. " +
                "Remove it; staff sign in with Cloudflare Access."
        }
        require(environment != Environment.PRODUCTION || access != null) {
            "The ops gateway needs Cloudflare Access in production: set OPS_ACCESS_TEAM_DOMAIN and OPS_ACCESS_AUD."
        }
        require(devLoginSecret == null || devLoginSecret.length >= MIN_SECRET_LENGTH) {
            "OPS_DEV_LOGIN_SECRET must be at least $MIN_SECRET_LENGTH characters"
        }
        allowedOrigins.forEach { origin ->
            require(Origins.normalise(origin) == origin) {
                "OPS_ALLOWED_ORIGINS must list origins like https://admin.circaso.com (was '$origin')"
            }
        }
    }

    // The dev login secret is a secret: never printed.
    override fun toString(): String =
        "OpsGatewayConfig(environment=$environment, access=${access != null}, origins=$allowedOrigins, " +
            "devLogin=$devLoginEnabled)"

    companion object {
        const val MIN_SECRET_LENGTH = 32
        const val DEFAULT_DEV_TTL_MINUTES = 480L
        const val DEFAULT_STAFF_PER_MINUTE = 300L
        const val DEFAULT_PUBLIC_PER_MINUTE = 30L

        fun from(config: Config, secrets: Secrets): OpsGatewayConfig {
            val team =
                config
                    .string("OPS_ACCESS_TEAM_DOMAIN")
                    ?.trim()
                    ?.removePrefix("https://")
                    ?.trimEnd('/')
            val audience = config.string("OPS_ACCESS_AUD")?.trim()
            return OpsGatewayConfig(
                environment = config.environment,
                access =
                    if (!team.isNullOrEmpty() &&
                        !audience.isNullOrEmpty()
                    ) {
                        AccessSettings(team, audience)
                    } else {
                        null
                    },
                allowedOrigins =
                    config
                        .string("OPS_ALLOWED_ORIGINS")
                        .orEmpty()
                        .split(',')
                        .map { it.trim().trimEnd('/') }
                        .filter { it.isNotEmpty() }
                        .toSet(),
                devLoginSecret = secrets.get("OPS_DEV_LOGIN_SECRET")?.takeIf { it.isNotBlank() },
                devLoginTtl = Duration.ofMinutes(config.long("OPS_DEV_LOGIN_TTL_MINUTES", DEFAULT_DEV_TTL_MINUTES)),
                staffPerMinute = config.long("OPS_RATE_LIMIT_PER_MINUTE", DEFAULT_STAFF_PER_MINUTE),
                publicPerMinute = config.long("OPS_PUBLIC_RATE_LIMIT_PER_MINUTE", DEFAULT_PUBLIC_PER_MINUTE),
            )
        }
    }
}

/** Origins compared exactly: scheme, host and port, lowercased, no path. */
object Origins {
    fun normalise(raw: String): String? =
        runCatching {
            val uri = URI(raw.trim())
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (scheme != "https" && scheme != "http") return null
            val port = if (uri.port == -1) "" else ":${uri.port}"
            "$scheme://$host$port"
        }.getOrNull()
}
