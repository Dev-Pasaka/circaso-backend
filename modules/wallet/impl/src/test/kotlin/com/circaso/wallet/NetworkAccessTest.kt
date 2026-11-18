package com.circaso.wallet

import com.circaso.integrations.api.CustodyAsset
import com.circaso.integrations.api.CustodyChain
import com.circaso.integrations.api.CustodySeeds
import com.circaso.integrations.api.CustodySetup
import com.circaso.integrations.api.StablecoinCustody
import com.circaso.integrations.api.SupportedChains
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.models.AccessSource
import com.circaso.wallet.domain.models.ChainFee
import com.circaso.wallet.domain.models.NetworkGrant
import com.circaso.wallet.domain.models.NetworkSettings
import com.circaso.wallet.domain.models.NetworkSnapshot
import com.circaso.wallet.domain.models.NetworkState
import com.circaso.wallet.domain.repositories.NetworkAccessRepository
import com.circaso.wallet.domain.repositories.NetworkSettingsRepository
import com.circaso.wallet.domain.support.NetworkAccess
import com.circaso.wallet.domain.support.Networks
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Who may use which network, and which network a request is for (decision 2026-11-18), over in-memory settings and
 * access rows: nothing changes for anyone while mainnet is off; staff are let in while it is in preview; once it is
 * on, new people are mainnet only and people with a testnet row keep both.
 */
class NetworkAccessTest {
    private val settings = MemoryNetworkSettings()
    private val grants = MemoryNetworkAccess()
    private val clock = MutableClock()
    private var custodyNetworks: Set<Network> = Network.entries.toSet()
    private val custody: StablecoinCustody =
        object : StablecoinCustody by FakeCustody() {
            override val networks: Set<Network> get() = custodyNetworks
        }
    private val networks = Networks(settings, TestSeeds, FakeFeeConfig(), custody, RecordingWarnings(), clock)
    private val access = NetworkAccess(networks, grants, clock)
    private val amina = UserId.new() // had a testnet wallet before networks
    private val zawadi = UserId.new() // joined later, no rows

    init {
        grants.rows += NetworkGrant(amina, Network.TESTNET, AccessSource.GRANDFATHERED, null, null, Instant.EPOCH)
    }

    private fun mainnet(state: NetworkState) =
        runBlocking {
            networks.fresh()
            settings.state(Network.MAINNET, state)
            networks.invalidate()
        }

    private suspend fun resolve(userId: UserId, header: String?) =
        when (val resolved = access.resolve(userId, header)) {
            is Resource.Success -> resolved.data.name
            is Resource.Error -> resolved.error.code
        }

    @Test
    fun `the networks start as testnet on and mainnet off, seeded once`() =
        runBlocking<Unit> {
            assertEquals(NetworkState.ON, networks.state(Network.TESTNET))
            assertEquals(NetworkState.OFF, networks.state(Network.MAINNET))
            networks.invalidate()
            networks.fresh()
            assertEquals(2, settings.seeds, "one seed per network, and none after the rows exist")
        }

    @Test
    fun `while mainnet is off everyone is on the testnet, as before, and mainnet is refused`() =
        runBlocking<Unit> {
            for (person in listOf(amina, zawadi)) {
                assertEquals(listOf(Network.TESTNET), access.usable(person))
                assertEquals("TESTNET", resolve(person, null))
                assertEquals("TESTNET", resolve(person, "TESTNET"))
                assertEquals("wallet.network_unavailable", resolve(person, "MAINNET"))
            }
            assertEquals(0, grants.reads, "before launch no testnet request reads an access row")
        }

    @Test
    fun `a header that names no network is malformed, and a blank one is as none`() =
        runBlocking<Unit> {
            assertEquals("request.malformed", resolve(amina, "mainnet"))
            assertEquals("request.malformed", resolve(amina, "BASE"))
            assertEquals("TESTNET", resolve(amina, "  "))
        }

    @Test
    fun `in preview only people granted mainnet may use it, and everyone still has the testnet`() =
        runBlocking<Unit> {
            mainnet(NetworkState.PREVIEW)
            grants.rows +=
                NetworkGrant(zawadi, Network.MAINNET, AccessSource.ADMIN, UUID.randomUUID(), "staff", Instant.EPOCH)

            assertEquals(listOf(Network.MAINNET, Network.TESTNET), access.usable(zawadi))
            assertEquals("MAINNET", resolve(zawadi, "MAINNET"))
            assertEquals("TESTNET", resolve(zawadi, null), "an app that names no network stays on the testnet")
            assertEquals(listOf(Network.TESTNET), access.usable(amina))
            assertEquals("wallet.network_unavailable", resolve(amina, "MAINNET"))
        }

    @Test
    fun `once mainnet is on, new people are mainnet only and people with a testnet row keep both`() =
        runBlocking<Unit> {
            mainnet(NetworkState.ON)

            assertEquals(listOf(Network.MAINNET), access.usable(zawadi))
            assertEquals("MAINNET", resolve(zawadi, null), "an old app of a new person is on mainnet")
            assertEquals("MAINNET", resolve(zawadi, "MAINNET"))
            assertEquals("wallet.network_unavailable", resolve(zawadi, "TESTNET"))

            assertEquals(listOf(Network.MAINNET, Network.TESTNET), access.usable(amina))
            assertEquals("TESTNET", resolve(amina, null), "an old app of a testnet person stays on the testnet")
            assertEquals("MAINNET", resolve(amina, "MAINNET"))
            assertEquals("TESTNET", resolve(amina, "TESTNET"))
        }

    @Test
    fun `a testnet wallet started before launch keeps the testnet after it, one started after gives nothing`() =
        runBlocking<Unit> {
            val early = UserId.new()
            val late = UserId.new()

            access.rememberTestnet(early, Network.TESTNET)
            access.rememberTestnet(early, Network.TESTNET)
            access.rememberTestnet(early, Network.MAINNET)
            mainnet(NetworkState.ON)
            access.rememberTestnet(late, Network.TESTNET)

            assertEquals(listOf(AccessSource.AUTO), grants.rows.filter { it.userId == early }.map { it.source })
            assertEquals(listOf(Network.MAINNET, Network.TESTNET), access.usable(early))
            assertTrue(grants.rows.none { it.userId == late })
            assertEquals(listOf(Network.MAINNET), access.usable(late))
        }

    @Test
    fun `revoking the testnet after launch hides it, and granting it brings it back`() =
        runBlocking<Unit> {
            mainnet(NetworkState.ON)

            grants.revoke(amina, Network.TESTNET)
            assertEquals(listOf(Network.MAINNET), access.usable(amina))
            assertEquals("wallet.network_unavailable", resolve(amina, "TESTNET"))

            grants.grant(
                NetworkGrant(amina, Network.TESTNET, AccessSource.ADMIN, UUID.randomUUID(), "asked", Instant.EPOCH),
            )
            assertEquals(listOf(Network.MAINNET, Network.TESTNET), access.usable(amina))
        }

    @Test
    fun `mainnet without its custody key is off whatever its row says`() =
        runBlocking<Unit> {
            mainnet(NetworkState.ON)
            custodyNetworks = setOf(Network.TESTNET)

            assertEquals(NetworkState.OFF, networks.state(Network.MAINNET))
            assertEquals(
                listOf(Network.TESTNET),
                access.usable(zawadi),
                "with mainnet unreachable the testnet is all there is",
            )
            assertEquals("wallet.network_unavailable", resolve(zawadi, "MAINNET"))
            assertEquals("TESTNET", resolve(zawadi, null))
        }

    @Test
    fun `with both networks off an app that names none is treated as before`() =
        runBlocking<Unit> {
            networks.fresh()
            settings.state(Network.TESTNET, NetworkState.OFF)
            networks.invalidate()

            assertEquals(emptyList(), access.usable(amina))
            assertEquals("TESTNET", resolve(amina, null))
            assertEquals("wallet.network_unavailable", resolve(amina, "TESTNET"))
        }

    @Test
    fun `settings that can't be read keep the ones in force`() =
        runBlocking<Unit> {
            mainnet(NetworkState.ON)
            networks.fresh()
            settings.failing = true
            clock.advance(Duration.ofMinutes(5))

            assertEquals(NetworkState.ON, networks.state(Network.MAINNET))
        }
}

private val MAINNET_TEST_CHAINS =
    SupportedChains(
        default = "BASE",
        chains =
            listOf(
                CustodyChain(
                    "BASE",
                    listOf(CustodyAsset("USDC", "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913", 6, true)),
                ),
                CustodyChain(
                    "SOL",
                    listOf(CustodyAsset("USDC", "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", 6, true)),
                ),
            ),
    )

object TestSeeds : CustodySeeds {
    override fun seed(network: Network): CustodySetup =
        if (network == Network.TESTNET) CustodySetup("test-app", TEST_CHAINS) else CustodySetup("", MAINNET_TEST_CHAINS)
}

class MemoryNetworkSettings : NetworkSettingsRepository {
    private val rows = LinkedHashMap<Network, NetworkSettings>()
    val fees = LinkedHashMap<String, ChainFee>()
    var seeds = 0
    var failing = false
    var inUse: Set<String> = emptySet()

    fun state(network: Network, state: NetworkState) {
        rows[network] = rows.getValue(network).copy(state = state)
    }

    override suspend fun load(): NetworkSnapshot {
        check(!failing) { "database down" }
        return NetworkSnapshot(rows.toMap(), fees.toMap())
    }

    override suspend fun seed(
        settings: NetworkSettings,
        fees: List<ChainFee>,
        at: Instant,
    ): Boolean {
        if (settings.network in rows) return false
        rows[settings.network] = settings
        fees.forEach { this.fees[it.blockchain] = it }
        seeds++
        return true
    }

    override suspend fun replace(
        settings: NetworkSettings,
        by: UUID,
        at: Instant,
    ): NetworkSettings = settings.copy(version = settings.version + 1).also { rows[settings.network] = it }

    override suspend fun lock(network: Network) = Unit

    override suspend fun chainsInUse(network: Network): Set<String> = inUse
}

class MemoryNetworkAccess : NetworkAccessRepository {
    val rows = mutableListOf<NetworkGrant>()
    var reads = 0

    override suspend fun grants(userId: UserId): List<NetworkGrant> {
        reads++
        return rows.filter { it.userId == userId }
    }

    override suspend fun grant(grant: NetworkGrant): Boolean {
        if (rows.any { it.userId == grant.userId && it.network == grant.network }) return false
        rows += grant
        return true
    }

    override suspend fun revoke(userId: UserId, network: Network): Boolean =
        rows.removeAll { it.userId == userId && it.network == network }
}
