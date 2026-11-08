package com.circaso.gateway

import com.circaso.infrastructure.api.Config
import com.circaso.gateway.access.AccessSettings
import com.circaso.infrastructure.api.Environment

/** Gateway settings, read from the `gateway` config section. */
class GatewayConfig(
    /** Minimum app version per platform (`android` → 1.0.0); a platform with no entry is never refused. */
    val minClientVersions: Map<String, ClientVersion> = emptyMap(),
    /** Per vendor, the source IPs its webhooks may come from; no entry means no IP check. */
    val webhookAllowedIps: Map<String, Set<String>> = emptyMap(),
    val apiDocs: ApiDocsConfig = ApiDocsConfig(),
) {
    companion object {
        private val platforms = listOf("android", "ios")
        private val vendors = listOf("africastalking", "resend", "circle", "cloudflare")

        fun from(config: Config): GatewayConfig {
            val gateway = config.section("gateway")
            val production = config.environment == Environment.PRODUCTION
            return GatewayConfig(
                apiDocs =
                    ApiDocsConfig(
                        // The reader and the raw spec map every route, support and ops ones included: never in production.
                        enabled = !production && gateway.boolean("api-docs.enabled", true),
                        // Behind the `ops` scope outside local runs, unless something in front of the app already decides who may read the docs
                        // (Cloudflare Access on the dev server): a browser cannot send a bearer token to open a page.
                        requireOps = gateway.boolean("api-docs.require-ops", !config.environment.isLocal),
                        access = accessFor(gateway.string("api-docs.access-team-domain"), gateway.string("api-docs.access-aud")),
                    ),
                minClientVersions =
                    platforms
                        .mapNotNull { platform ->
                            gateway.string("min-client-version.$platform")?.let { raw ->
                                platform to (
                                    ClientVersion.parseVersion(raw)
                                        ?: throw IllegalStateException(
                                            "gateway.min-client-version.$platform must look like 1.4.0 (was " +
                                                "'$raw')",
                                        )
                                )
                            }
                        }.toMap(),
                webhookAllowedIps =
                    vendors
                        .mapNotNull { vendor ->
                            gateway.string("webhooks.$vendor.allowed-ips")?.let { raw ->
                                vendor to
                                    raw
                                        .split(',')
                                        .map { it.trim() }
                                        .filter { it.isNotEmpty() }
                                        .toSet()
                            }
                        }.toMap(),
            )
        }
    }
}

/** Both halves or neither: a team without an audience (or the reverse) would verify nothing. */
private fun accessFor(team: String?, audience: String?): AccessSettings? {
    val t = team?.trim()?.removePrefix("https://")?.trimEnd('/')
    val a = audience?.trim()
    if (t.isNullOrEmpty() && a.isNullOrEmpty()) return null
    require(!t.isNullOrEmpty() && !a.isNullOrEmpty()) {
        "Set both GATEWAY_API_DOCS_ACCESS_TEAM_DOMAIN and GATEWAY_API_DOCS_ACCESS_AUD, or neither."
    }
    return AccessSettings(t, a)
}

/** The API reader at `/docs` and the spec behind it at `/docs/openapi.json`. */
data class ApiDocsConfig(
    val enabled: Boolean = false,
    /** Outside local runs the reader sits behind the `ops` scope, unless `gateway.api-docs.require-ops` is false (`GATEWAY_API_DOCS_REQUIRE_OPS`). */
    val requireOps: Boolean = true,
    /**
     * The Cloudflare Access application of the docs site (docs.circaso.com), from `GATEWAY_API_DOCS_ACCESS_TEAM_DOMAIN`
     * and `GATEWAY_API_DOCS_ACCESS_AUD`. When set, the spec is served only to a request carrying that application's
     * token (verified: signature, audience, issuer, expiry), and [requireOps] no longer applies (decision 2026-11-08).
     */
    val access: AccessSettings? = null,
)
