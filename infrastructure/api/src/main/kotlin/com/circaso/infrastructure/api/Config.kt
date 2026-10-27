package com.circaso.infrastructure.api

/** Where the process is running. Decides the schema mode, the logging format and whether API docs are served. */
enum class Environment {
    LOCAL,
    STAGING,
    PRODUCTION,
    ;

    val isLocal: Boolean get() = this == LOCAL

    companion object {
        fun parse(raw: String): Environment =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("CIRCASO_ENV must be one of local, staging, production (was '$raw')")
    }
}

/**
 * Typed configuration, a section per module and vendor. Keys are dotted lowercase paths
 * (`database.url`, `integrations.sms.provider`); the environment-backed implementation reads
 * `DATABASE_URL`, `INTEGRATIONS_SMS_PROVIDER`. Credentials are not config: they come from [Secrets].
 */
interface Config {
    val environment: Environment

    fun string(key: String): String?

    fun string(key: String, default: String): String = string(key) ?: default

    fun requireString(key: String): String = string(key) ?: throw IllegalStateException("Missing configuration '$key'")

    fun int(key: String, default: Int): Int =
        string(key)?.let {
            it.toIntOrNull() ?: throw IllegalStateException("Configuration '$key' must be a number (was '$it')")
        } ?: default

    fun boolean(key: String, default: Boolean): Boolean =
        string(key)?.let {
            when (it.lowercase()) {
                "true" -> true
                "false" -> false
                else -> throw IllegalStateException("Configuration '$key' must be true or false (was '$it')")
            }
        } ?: default

    /** The same configuration, with [prefix] put in front of every key. */
    fun section(prefix: String): Config
}

/**
 * Credentials and keys. Environment secrets for the pilot; a key store later, behind this same interface.
 * Values are never logged: callers pass them straight to the client that needs them.
 */
interface Secrets {
    fun get(name: String): String?

    fun require(name: String): String = get(name) ?: throw IllegalStateException("Missing secret '$name'")
}
