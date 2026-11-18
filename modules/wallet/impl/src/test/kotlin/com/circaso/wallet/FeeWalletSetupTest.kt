package com.circaso.wallet

import com.circaso.integrations.api.TreasuryAccess
import com.circaso.shared.Network
import com.circaso.wallet.domain.models.ChainSettings
import com.circaso.wallet.domain.models.FeeWalletOrigin
import com.circaso.wallet.domain.models.FeeWalletStatus
import com.circaso.wallet.domain.support.FeeChains
import com.circaso.wallet.domain.support.Networks
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fee wallets come into being by themselves (decision 2026-11-18): created on a network that has none, imported
 * where an address is already charged to, and left exactly as they are on every run after that.
 */
class FeeWalletSetupTest {
    private val testnetFee = "0xFee0000000000000000000000000000000000fEE"
    private val testnetFeeStored = testnetFee.lowercase()

    private fun mainnet() = FeeWalletWorld().apply { treasury.grant(Network.MAINNET, TreasuryAccess.FULL) }

    /** The live testnet: fee addresses seeded from the old variables, and no fee wallet rows. */
    private fun testnet(access: TreasuryAccess) =
        FeeWalletWorld(seedFees = mapOf("BASE-SEPOLIA" to testnetFee, "MATIC-AMOY" to testnetFee)).apply {
            treasury.grant(Network.TESTNET, access)
        }

    @Test
    fun `a network with nothing gets one wallet at one address on every chain that can carry a fee`() =
        runBlocking<Unit> {
            val world = mainnet()

            val state = world.setup.ensure(Network.MAINNET)

            assertEquals(FeeWalletStatus.READY, state.status)
            assertNull(state.reason)
            val rows = world.wallets.wallets(Network.MAINNET)
            assertEquals(listOf("BASE", "ETH", "MATIC"), rows.map { it.blockchain }, "never Solana")
            assertEquals(1, rows.map { it.address }.toSet().size, "the same address on every chain")
            assertTrue(rows.all { it.circleWalletId != null && it.origin == FeeWalletOrigin.CREATED })
            assertEquals(3, rows.mapNotNull { it.circleWalletId }.toSet().size)
            rows.forEach { assertEquals(it.address, world.feeAddress(it.blockchain), "fees are charged to it") }
            assertNull(world.feeAddress("SOL"))
            assertEquals("set-1", world.wallets.network(Network.MAINNET)?.walletSetId)
            assertEquals(
                listOf("findWallets", "createWalletSet", "createWallets", "deriveWallet", "deriveWallet"),
                world.treasury.calls,
                "the set, one wallet on the default chain, then derived on the others",
            )
            assertEquals(
                "BASE",
                world.treasury.wallets
                    .first()
                    .blockchain,
                "created on the default chain",
            )
            assertTrue(world.treasury.wallets.all { it.refId == "circaso-fees" && it.accountType == "EOA" })
        }

    @Test
    fun `running again creates nothing and asks Circle nothing`() =
        runBlocking<Unit> {
            val world = mainnet()
            world.setup.ensure(Network.MAINNET)
            val before = world.wallets.wallets(Network.MAINNET)
            val calls = world.treasury.calls.size

            repeat(3) { assertEquals(FeeWalletStatus.READY, world.setup.ensure(Network.MAINNET).status) }

            assertEquals(before, world.wallets.wallets(Network.MAINNET))
            assertEquals(calls, world.treasury.calls.size)
            assertEquals(3, world.treasury.wallets.size)
        }

    @Test
    fun `two servers starting together end with one wallet set and one address`() =
        runBlocking<Unit> {
            val world = mainnet()
            world.networks.fresh()

            listOf(world.setup, world.secondSetup()).map { async { it.ensure(Network.MAINNET) } }.awaitAll()

            assertEquals(3, world.treasury.wallets.size, "Circle made each wallet once")
            assertEquals(
                1,
                world.treasury.wallets
                    .map { it.address }
                    .toSet()
                    .size,
            )
            assertEquals(
                1,
                world.treasury.wallets
                    .map { it.walletSetId }
                    .toSet()
                    .size,
            )
            assertEquals(3, world.wallets.wallets(Network.MAINNET).size)
            assertEquals(FeeWalletStatus.READY, world.wallets.network(Network.MAINNET)?.status)
        }

    @Test
    fun `the creation keys are the same every time, differ by step and network, and look like random UUIDs`() {
        val set = FeeChains.creationKey(Network.MAINNET, "wallet-set")

        assertEquals(set, FeeChains.creationKey(Network.MAINNET, "wallet-set"))
        assertNotEquals(set, FeeChains.creationKey(Network.TESTNET, "wallet-set"))
        assertNotEquals(set, FeeChains.creationKey(Network.MAINNET, "wallet:BASE"))
        assertEquals(4, set.version())
        assertEquals(2, set.variant())
        assertEquals(set, UUID.fromString(set.toString()))
    }

    @Test
    fun `a server that lost its rows asks with the same keys and gets the same wallets back`() =
        runBlocking<Unit> {
            val world = mainnet()
            world.setup.ensure(Network.MAINNET)
            val address = world.feeAddress("BASE")
            // The rows and the addresses are gone and Circle's list doesn't show the wallets: only the keys are left
            // to stop a second wallet set.
            world.wallets.rows.clear()
            world.wallets.networks.clear()
            world.settings.fees.replaceAll { _, fee -> fee.copy(feeAddress = null) }
            world.treasury.listable = false

            val state = world.setup.ensure(Network.MAINNET)

            assertEquals(FeeWalletStatus.READY, state.status)
            assertEquals("set-1", world.wallets.network(Network.MAINNET)?.walletSetId, "the same set, not a second")
            assertEquals(3, world.treasury.wallets.size, "and the same three wallets")
            assertEquals(
                setOf(address),
                world.wallets.rows.values
                    .map { it.address }
                    .toSet(),
            )
            assertEquals(address, world.feeAddress("BASE"))
        }

    @Test
    fun `wallets made earlier under our refId are adopted instead of made again`() =
        runBlocking<Unit> {
            val world = mainnet()
            val earlier = "0x00000000000000000000000000000000000000e1"
            world.treasury.existing("BASE", earlier)
            world.treasury.existing("BASE", "0x00000000000000000000000000000000000000d2", developerOwned = false)

            val state = world.setup.ensure(Network.MAINNET)

            assertEquals(FeeWalletStatus.READY, state.status)
            assertEquals(0, world.treasury.count("createWalletSet"))
            assertEquals(0, world.treasury.count("createWallets"))
            val rows = world.wallets.wallets(Network.MAINNET).associateBy { it.blockchain }
            assertEquals(FeeWalletOrigin.IMPORTED, rows.getValue("BASE").origin)
            assertEquals(setOf(earlier), rows.values.map { it.address }.toSet(), "the others derived from it")
            assertEquals("set-old", world.wallets.network(Network.MAINNET)?.walletSetId)
        }

    @Test
    fun `the testnet's fee wallets are imported by their address and the address is never replaced`() =
        runBlocking<Unit> {
            val world = testnet(TreasuryAccess.FULL)
            world.treasury.existing("BASE-SEPOLIA", testnetFeeStored)
            world.treasury.existing("MATIC-AMOY", testnetFeeStored)

            val state = world.setup.ensure(Network.TESTNET)

            assertEquals(FeeWalletStatus.READY, state.status)
            assertNull(state.reason)
            val rows = world.wallets.wallets(Network.TESTNET)
            assertEquals(listOf("BASE-SEPOLIA", "MATIC-AMOY"), rows.map { it.blockchain })
            assertTrue(rows.all { it.origin == FeeWalletOrigin.IMPORTED && it.address == testnetFeeStored })
            assertEquals(listOf("w-1", "w-2"), rows.map { it.circleWalletId })
            assertEquals(testnetFee, world.feeAddress("BASE-SEPOLIA"), "exactly as it was, case and all")
            assertEquals(testnetFee, world.feeAddress("MATIC-AMOY"))
            assertEquals(0, world.treasury.count("createWalletSet") + world.treasury.count("createWallets"))
            assertEquals(2, world.treasury.wallets.size, "nothing new at Circle")
        }

    @Test
    fun `without the testnet's entity secret the wallets are still found, and only withdrawals are missing`() =
        runBlocking<Unit> {
            val world = testnet(TreasuryAccess.READ_ONLY)
            world.treasury.existing("BASE-SEPOLIA", testnetFeeStored)
            world.treasury.existing("MATIC-AMOY", testnetFeeStored)

            val state = world.setup.ensure(Network.TESTNET)

            assertEquals(FeeWalletStatus.READY, state.status, "fees keep being collected")
            assertTrue("CIRCLE_ENTITY_SECRET" in state.reason.orEmpty(), state.reason)
            assertTrue("withdrawals aren't available" in state.reason.orEmpty(), state.reason)
            assertTrue(world.wallets.wallets(Network.TESTNET).all { it.circleWalletId != null })
            assertEquals(testnetFee, world.feeAddress("BASE-SEPOLIA"))
        }

    @Test
    fun `an address Circle doesn't have is kept as it is, with no wallet behind it`() =
        runBlocking<Unit> {
            val world = testnet(TreasuryAccess.FULL)

            val state = world.setup.ensure(Network.TESTNET)

            assertEquals(FeeWalletStatus.READY, state.status)
            assertTrue("isn't a wallet of this Circle account" in state.reason.orEmpty(), state.reason)
            val rows = world.wallets.wallets(Network.TESTNET)
            assertTrue(rows.all { it.origin == FeeWalletOrigin.ADDRESS_ONLY && it.circleWalletId == null })
            assertEquals(testnetFee, world.feeAddress("BASE-SEPOLIA"))
            assertEquals(0, world.treasury.count("createWalletSet") + world.treasury.count("createWallets"))
            assertTrue(world.treasury.wallets.isEmpty(), "no new wallet beside an address already charged to")

            // It turns up at Circle later (the right API key): the same row takes the wallet's id.
            world.treasury.existing("BASE-SEPOLIA", testnetFeeStored)
            world.setup.ensure(Network.TESTNET)
            val base = world.wallets.rows.getValue("BASE-SEPOLIA")
            assertEquals(FeeWalletOrigin.IMPORTED to "w-1", base.origin to base.circleWalletId)
            assertEquals(testnetFee, world.feeAddress("BASE-SEPOLIA"))
        }

    @Test
    fun `no key for the network - the addresses stay, nothing is asked, and the server carries on`() =
        runBlocking<Unit> {
            val world = testnet(TreasuryAccess.NO_ACCOUNT)

            val testnet = world.setup.ensure(Network.TESTNET)
            val mainnet = world.setup.ensure(Network.MAINNET)

            assertEquals(FeeWalletStatus.READY, testnet.status)
            assertTrue("no Circle API key for TESTNET" in testnet.reason.orEmpty(), testnet.reason)
            assertEquals(FeeWalletStatus.NOT_CREATED, mainnet.status)
            assertTrue("no Circle API key for MAINNET" in mainnet.reason.orEmpty(), mainnet.reason)
            assertTrue("No fee is taken on BASE, MATIC, ETH" in mainnet.reason.orEmpty(), mainnet.reason)
            assertTrue(world.treasury.calls.isEmpty())
            assertEquals(testnetFee, world.feeAddress("BASE-SEPOLIA"))
        }

    @Test
    fun `no entity secret and nothing to import - not created, and it says which secret is missing`() =
        runBlocking<Unit> {
            val world = FeeWalletWorld().apply { treasury.grant(Network.MAINNET, TreasuryAccess.READ_ONLY) }

            val state = world.setup.ensure(Network.MAINNET)

            assertEquals(FeeWalletStatus.NOT_CREATED, state.status)
            assertTrue("CIRCLE_MAINNET_ENTITY_SECRET" in state.reason.orEmpty(), state.reason)
            assertTrue(world.wallets.wallets(Network.MAINNET).isEmpty())
            assertNull(world.feeAddress("BASE"))
            assertEquals(listOf("findWallets"), world.treasury.calls, "it looked, and signed nothing")
        }

    @Test
    fun `a refusal is FAILED with Circle's status and code, and the next run finishes the job`() =
        runBlocking<Unit> {
            val world = mainnet()
            world.treasury.failing["createWallets"] = FakeTreasury.REFUSED

            val failed = world.setup.ensure(Network.MAINNET)

            assertEquals(FeeWalletStatus.FAILED, failed.status)
            assertTrue("HTTP 400, code 156004" in failed.reason.orEmpty(), failed.reason)
            assertEquals("set-1", world.wallets.network(Network.MAINNET)?.walletSetId, "the set made is kept")
            assertTrue(world.wallets.wallets(Network.MAINNET).isEmpty())

            world.treasury.failing.clear()
            val ready = world.setup.ensure(Network.MAINNET)

            assertEquals(FeeWalletStatus.READY, ready.status)
            assertNull(ready.reason)
            assertEquals(1, world.treasury.count("createWalletSet"), "the set isn't made twice")
            assertEquals("set-1", world.wallets.network(Network.MAINNET)?.walletSetId)
        }

    @Test
    fun `Circle being down fails the run without throwing, and a half-made network is finished later`() =
        runBlocking<Unit> {
            val world = mainnet()
            world.treasury.failing["deriveWallet"] = FakeTreasury.DOWN

            val failed = world.setup.ensure(Network.MAINNET)

            assertEquals(FeeWalletStatus.FAILED, failed.status)
            assertTrue("MATIC" in failed.reason.orEmpty() && "ETH" in failed.reason.orEmpty(), failed.reason)
            assertEquals(listOf("BASE"), world.wallets.wallets(Network.MAINNET).map { it.blockchain })
            assertNotNull(world.feeAddress("BASE"), "the default chain already collects")

            world.treasury.failing.clear()
            assertEquals(FeeWalletStatus.READY, world.setup.ensure(Network.MAINNET).status)
            assertEquals(
                1,
                world.wallets
                    .wallets(Network.MAINNET)
                    .map { it.address }
                    .toSet()
                    .size,
            )
            assertEquals(1, world.treasury.count("createWallets"))
        }

    @Test
    fun `when Circle can't say what exists already, nothing is created blind`() =
        runBlocking<Unit> {
            val world = mainnet()
            world.networks.fresh()
            world.treasury.failing["findWallets"] = FakeTreasury.DOWN

            val state = world.setup.ensure(Network.MAINNET)

            assertEquals(FeeWalletStatus.FAILED, state.status)
            assertFalse(world.treasury.count("createWalletSet") > 0, "it doesn't create blind")
        }

    @Test
    fun `a chain enabled later gets its wallet at the same address on the next run`() =
        runBlocking<Unit> {
            val world = mainnet()
            world.setup.ensure(Network.MAINNET)
            val address = world.feeAddress("BASE")
            val current = world.networks.settings(Network.MAINNET)
            val arbitrum = ChainSettings("ARB", true, true, "SCA", emptyList())
            val solana2 = ChainSettings("SOL-2", false, true, "EOA", emptyList())
            world.settings.replace(
                current.copy(chains = current.chains + arbitrum + solana2),
                UUID.randomUUID(),
                world.clock.now,
            )
            world.clock.advance(Networks.MAX_AGE)

            val state = world.setup.ensure(Network.MAINNET)

            assertEquals(FeeWalletStatus.READY, state.status)
            assertEquals(
                address,
                world.wallets.rows
                    .getValue("ARB")
                    .address,
            )
            assertEquals(address, world.feeAddress("ARB"))
            assertEquals(1, world.treasury.count("createWallets"), "derived, not created")
            assertNull(world.wallets.rows["SOL-2"])
        }

    @Test
    fun `a fee address cleared by hand is not put back`() =
        runBlocking<Unit> {
            val world = mainnet()
            world.setup.ensure(Network.MAINNET)
            world.settings.fees["MATIC"] =
                world.settings.fees
                    .getValue("MATIC")
                    .copy(feeAddress = null)

            world.setup.ensure(Network.MAINNET)

            assertNull(world.feeAddress("MATIC"), "no fee on a chain is staff's decision to make")
        }

    @Test
    fun `no secret ever reaches a reason`() =
        runBlocking<Unit> {
            val world = mainnet()
            world.treasury.failing["createWalletSet"] = FakeTreasury.REFUSED

            val reason =
                world.setup
                    .ensure(Network.MAINNET)
                    .reason
                    .orEmpty()

            assertEquals(
                "The wallet set couldn't be created (The custody provider refused the request (HTTP 400, code " +
                    "156004).) No fee is taken on BASE, MATIC, ETH until it has a fee wallet.",
                reason,
            )
        }
}
