package com.circaso.app

import com.circaso.infrastructure.api.Environment

/**
 * The process-level settings `app` needs to start. Everything else (database, Redis, vendors) is read
 * by infrastructure's `Config` and `Secrets`.
 */
data class AppConfig(
    val environment: Environment = Environment.LOCAL,
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val version: String = "dev",
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            val defaults = AppConfig()
            val port = env["PORT"]?.let {
                it.toIntOrNull()?.takeIf { port -> port in 1..65535 } ?: throw IllegalArgumentException("PORT must be 1-65535 (was '$it')")
            }
            return AppConfig(
                environment = env["CIRCASO_ENV"]?.let(Environment::parse) ?: defaults.environment,
                host = env["HOST"] ?: defaults.host,
                port = port ?: defaults.port,
                version = env["APP_VERSION"] ?: defaults.version,
            )
        }
    }
}
