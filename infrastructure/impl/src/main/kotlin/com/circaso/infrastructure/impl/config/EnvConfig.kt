package com.circaso.infrastructure.impl.config

import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.Environment
import com.circaso.infrastructure.api.Secrets

/**
 * [Config] read from environment variables: `scylla.contact-points` is `SCYLLA_CONTACT_POINTS`, and a
 * [section] puts its prefix in front. `CIRCASO_ENV` picks the [environment] (default local).
 */
class EnvConfig(
    private val env: Map<String, String> = System.getenv(),
    private val prefix: String = "",
    override val environment: Environment = env["CIRCASO_ENV"]?.let(Environment::parse) ?: Environment.LOCAL,
) : Config {
    override fun string(key: String): String? = env[variableName(prefix + key)]?.takeIf { it.isNotBlank() }

    override fun section(prefix: String): Config = EnvConfig(env, "${this.prefix}$prefix.", environment)

    private fun variableName(key: String) = key.uppercase().replace('.', '_').replace('-', '_')
}

/**
 * [Secrets] from the environment: `scylla.password` is `SCYLLA_PASSWORD`, `phone-hmac-key` is `PHONE_HMAC_KEY`.
 * A key store replaces this later.
 */
class EnvSecrets(private val env: Map<String, String> = System.getenv()) : Secrets {
    override fun get(name: String): String? =
        (env[name] ?: env[name.uppercase().replace('.', '_').replace('-', '_')])?.takeIf { it.isNotEmpty() }

    // A secret never prints, even if something logs this object.
    override fun toString(): String = "EnvSecrets(***)"
}
