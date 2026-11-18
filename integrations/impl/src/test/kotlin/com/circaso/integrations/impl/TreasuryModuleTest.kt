package com.circaso.integrations.impl

import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.Environment
import com.circaso.infrastructure.api.HttpClients
import com.circaso.infrastructure.api.Secrets
import com.circaso.integrations.api.StablecoinCustody
import com.circaso.integrations.api.TreasuryAccess
import com.circaso.integrations.api.TreasuryCustody
import com.circaso.integrations.api.WebhookVendor
import com.circaso.shared.Network
import io.ktor.client.engine.mock.respondOk
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where the fee wallets' entity secrets are read (decision 2026-11-18): in the ops process's `treasuryModule` and
 * nowhere else, so the app's processes start and behave the same with or without them.
 */
class TreasuryModuleTest {
    private class Settings(private val values: Map<String, String>, private val prefix: String = "") : Config {
        override val environment = Environment.LOCAL

        override fun string(key: String): String? = values[prefix + key]

        override fun section(prefix: String): Config = Settings(values, this.prefix + prefix + ".")
    }

    /** Secrets that remember every name asked for. */
    private class Asked(private val values: Map<String, String>) : Secrets {
        val names = CopyOnWriteArrayList<String>()

        override fun get(name: String): String? {
            names += name
            return values[name]
        }
    }

    private val circle =
        Settings(
            mapOf(
                "integrations.custody.provider" to "circle",
                "integrations.custody.app-id" to "a",
            ),
        )
    private val keys = mapOf("circle.api-key" to "TEST_API_KEY:a:b", "circle.mainnet-api-key" to "LIVE_API_KEY:a:b")
    private val secret = "0f".repeat(32)

    private fun koin(
        secrets: Secrets,
        config: Config = circle,
        ops: Boolean,
    ): Koin =
        koinApplication {
            modules(
                listOfNotNull(
                    integrationsModule(config, secrets),
                    if (ops) treasuryModule(config, secrets) else null,
                    module { single<HttpClients> { FakeHttpClients { _ -> respondOk() } } },
                ),
            )
        }.koin

    @Test
    fun `the app's wiring never asks for an entity secret and has no treasury`() {
        val secrets = Asked(keys + ("circle.entity-secret" to secret) + ("circle.mainnet-entity-secret" to secret))

        val koin = koin(secrets, ops = false)
        val custody = koin.get<StablecoinCustody>()
        koin.getAll<WebhookVendor>()

        assertEquals(Network.entries.toSet(), custody.networks)
        assertNull(koin.getOrNull<TreasuryCustody>())
        assertFalse(secrets.names.any { "entity" in it }, "asked for ${secrets.names}")
    }

    @Test
    fun `the app's wiring is the same with no entity secret at all`() {
        val without = koin(Asked(keys), ops = false)
        val with = koin(Asked(keys + ("circle.entity-secret" to secret)), ops = false)

        assertEquals(with.get<StablecoinCustody>().networks, without.get<StablecoinCustody>().networks)
        assertEquals(
            with.getAll<WebhookVendor>().map { it.name }.sorted(),
            without.getAll<WebhookVendor>().map { it.name }.sorted(),
        )
    }

    @Test
    fun `the ops process signs where it has the secret and reads where it has only the key`() {
        val treasury =
            koin(Asked(keys + ("circle.mainnet-entity-secret" to secret)), ops = true).get<TreasuryCustody>()

        assertEquals(TreasuryAccess.FULL, treasury.access(Network.MAINNET))
        assertEquals(TreasuryAccess.READ_ONLY, treasury.access(Network.TESTNET), "no CIRCLE_ENTITY_SECRET")
    }

    @Test
    fun `the ops process starts without any secret, and one that isn't 64 hex characters counts as absent`() {
        val none = koin(Asked(keys), ops = true).get<TreasuryCustody>()
        val broken =
            koin(Asked(keys + ("circle.mainnet-entity-secret" to "not-a-secret")), ops = true).get<TreasuryCustody>()
        val testnetOnly = koin(Asked(mapOf("circle.api-key" to "TEST_API_KEY:a:b")), ops = true).get<TreasuryCustody>()

        assertEquals(TreasuryAccess.READ_ONLY, none.access(Network.MAINNET))
        assertEquals(TreasuryAccess.READ_ONLY, broken.access(Network.MAINNET))
        assertEquals(TreasuryAccess.NO_ACCOUNT, testnetOnly.access(Network.MAINNET), "no CIRCLE_MAINNET_API_KEY")
        assertTrue(testnetOnly.canRead(Network.TESTNET))
    }

    @Test
    fun `without Circle as the custody provider there is no treasury`() {
        val log = Settings(emptyMap())

        assertNull(koin(Asked(emptyMap()), log, ops = true).getOrNull<TreasuryCustody>())
    }

    @Test
    fun `the secrets are read under their fixed names`() {
        assertEquals("circle.entity-secret", entitySecretName(Network.TESTNET))
        assertEquals("circle.mainnet-entity-secret", entitySecretName(Network.MAINNET))
    }
}
