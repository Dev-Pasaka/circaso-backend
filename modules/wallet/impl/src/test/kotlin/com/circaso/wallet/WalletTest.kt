package com.circaso.wallet

import com.circaso.shared.UserId
import com.circaso.shared.decode
import com.circaso.wallet.api.WalletCreated
import com.circaso.wallet.domain.models.ChainStatus
import com.circaso.wallet.domain.models.WalletStatus
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** W1–W3 and W12 over in-memory fakes: create, re-create while setting up, already exists, chains, the fiat value. */
class WalletTest {
    private val h = Harness()
    private val amina = UserId.new()

    private fun usdc(amount: String) = BigDecimal(amount).movePointRight(6).toBigIntegerExact()

    // ---- W2 create ----

    @Test
    fun `W1 says NONE before the wallet is asked for, and W2 makes the Circle user and the PIN challenge`() =
        runBlocking<Unit> {
            assertEquals(WalletStatus.NONE, h.wallet(amina).data().status)
            val key = UUID.randomUUID()

            val setup = h.create(amina, key).data()

            assertEquals(WalletStatus.SETTING_UP, setup.status)
            assertEquals("challenge-1", setup.challengeId)
            assertEquals("app-1", setup.sdk.appId)
            val circleUserId = h.circleUserOf(amina)
            assertTrue(circleUserId in h.custody.users)
            assertTrue(circleUserId != amina.toString(), "Circle never sees our user id")
            assertEquals(listOf(key), h.custody.keys, "Circle's key is the one the request derived")
            assertEquals(WalletStatus.SETTING_UP, h.wallet(amina).data().status)
        }

    @Test
    fun `W2 again while setting up gives a fresh challenge for the same Circle user`() =
        runBlocking<Unit> {
            val first = h.create(amina).data()
            val circleUserId = h.circleUserOf(amina)

            val second = h.create(amina).data()

            assertEquals(WalletStatus.SETTING_UP, second.status)
            assertTrue(first.challengeId != second.challengeId)
            assertEquals(circleUserId, h.circleUserOf(amina))
            assertEquals(1, h.custody.users.size)
        }

    @Test
    fun `once the user signs, W1 records the wallet, publishes WalletCreated once, and W2 says already exists`() =
        runBlocking<Unit> {
            h.create(amina).data()
            val signed = h.custody.sign(h.circleUserOf(amina), address = "0xAbCdEf0000000000000000000000000000000001")

            val wallet = h.wallet(amina).data()
            h.wallet(amina).data()

            assertEquals(WalletStatus.ACTIVE, wallet.status)
            val chain = wallet.chains.single()
            assertEquals("BASE-SEPOLIA", chain.wallet.blockchain)
            assertEquals(signed.walletId, chain.wallet.circleWalletId)
            assertEquals("0xabcdef0000000000000000000000000000000001", chain.wallet.address, "EVM in lowercase")
            val event = h.bus.published.single()
            assertEquals(WalletCreated(amina, "BASE-SEPOLIA"), event.decode<WalletCreated>())
            assertEquals(h.bus.published, (h.outbox as RecordingOutbox).written, "outbox row, then the publish")
            assertEquals("wallet.already_exists", h.create(amina).code())
            assertTrue(h.hasWallet(amina))
        }

    @Test
    fun `W2 when Circle has nothing left to sign records the wallet it lists and says already exists`() =
        runBlocking<Unit> {
            h.create(amina).data()
            h.custody.sign(h.circleUserOf(amina))

            assertEquals("wallet.already_exists", h.create(amina).code())
            assertEquals(1, h.wallets.wallets(amina).size)
        }

    @Test
    fun `W2 with the PIN set but the wallet not listed yet is still setting up, with no challenge`() =
        runBlocking<Unit> {
            h.create(amina).data()
            h.custody.initialized += h.circleUserOf(amina)

            val setup = h.create(amina).data()

            assertEquals(WalletStatus.SETTING_UP, setup.status)
            assertNull(setup.challengeId)
            assertTrue(h.bus.published.isEmpty())
        }

    @Test
    fun `Circle being down answers custody unavailable`() =
        runBlocking<Unit> {
            h.custody.down = true
            assertEquals("wallet.custody_unavailable", h.create(amina).code())
        }

    // ---- W1 balances and fiat ----

    @Test
    fun `W1 lists every chain with every offered asset, adds them up, and prices USDC in the asked currency`() =
        runBlocking<Unit> {
            val circleUserId = h.activeWallet(amina)
            val base = h.wallets.wallets(amina).single()
            val solana = h.custody.sign(circleUserId, "SOL-DEVNET")
            h.adding.add(amina, "SOL-DEVNET")
            h.custody.held[base.circleWalletId] = mapOf("USDC" to usdc("20.5"))
            h.custody.held[solana.walletId] = mapOf("USDC" to usdc("1.25"))
            h.rates.rates["KES"] = BigDecimal("129.25")
            h.cache.values.clear() // the 15 seconds since activeWallet's read have passed

            val wallet = h.wallet(amina, "KES").data()

            assertEquals(listOf("BASE-SEPOLIA", "SOL-DEVNET"), wallet.chains.map { it.wallet.blockchain })
            assertEquals(listOf("USDC", "ETH"), wallet.chains[0].holdings.map { it.symbol }, "zero ETH included")
            assertEquals(usdc("21.75"), wallet.totals.first { it.symbol == "USDC" }.amountMinor)
            val fiat = assertNotNull(wallet.fiat)
            assertEquals("KES", fiat.currency)
            assertEquals(BigDecimal("2811.19"), fiat.amount, "21.75 x 129.25 = 2811.1875, to the cent")
            assertEquals(BigDecimal("129.25"), fiat.rate)
            assertEquals(h.rates.asOf, fiat.asOf)
            assertFalse(fiat.partial)
            assertEquals(BigDecimal("2649.63"), wallet.chains[0].holdings[0].fiatAmount)
            assertNull(wallet.chains[0].holdings[1].fiatAmount, "ETH has no price")
            assertTrue(h.adding.list(amina).isEmpty(), "the added chain is no longer pending")
        }

    @Test
    fun `an asset with no price that is held makes the fiat value partial`() =
        runBlocking<Unit> {
            h.activeWallet(amina)
            val base = h.wallets.wallets(amina).single()
            h.custody.held[base.circleWalletId] = mapOf("USDC" to usdc("1"), "ETH" to BigInteger.TEN.pow(15))
            h.rates.rates["UGX"] = BigDecimal("3700.5")
            h.cache.values.clear()

            val wallet = h.wallet(amina, "UGX").data()

            val fiat = assertNotNull(wallet.fiat)
            assertTrue(fiat.partial)
            assertEquals(BigDecimal("3701"), fiat.amount, "UGX has no minor unit")
            assertEquals(BigInteger.TEN.pow(15), wallet.totals.first { it.symbol == "ETH" }.amountMinor)
        }

    @Test
    fun `no rate leaves the fiat value out and is never an error`() =
        runBlocking<Unit> {
            h.activeWallet(amina)
            val wallet = h.wallet(amina, "XAF").data()
            assertNull(wallet.fiat)
            assertNull(h.wallet(amina).data().fiat, "nothing asked, nothing given")
        }

    @Test
    fun `balances are read through the 15-second cache`() =
        runBlocking<Unit> {
            h.activeWallet(amina)
            val before = h.custody.count("balances")
            h.wallet(amina).data()
            h.wallet(amina).data()
            assertEquals(before, h.custody.count("balances"))
        }

    @Test
    fun `W1 on an active wallet with Circle down answers custody unavailable, but never while setting up`() =
        runBlocking<Unit> {
            h.create(amina).data()
            h.custody.down = true
            assertEquals(WalletStatus.SETTING_UP, h.wallet(amina).data().status)

            h.custody.down = false
            h.custody.sign(h.circleUserOf(amina))
            h.wallet(amina).data()
            h.cache.values.clear()
            h.custody.down = true
            assertEquals("wallet.custody_unavailable", h.wallet(amina).code())
        }

    // ---- W3 session ----

    @Test
    fun `W3 needs a wallet asked for, then hands the SDK its session`() =
        runBlocking<Unit> {
            assertEquals("wallet.not_found", h.openSession(amina).code())
            h.create(amina).data()
            val session = h.openSession(amina).data()
            assertEquals("token-${h.circleUserOf(amina)}", session.userToken)
        }

    // ---- W12 chains ----

    @Test
    fun `W12 lists the supported chains with what this user's wallet is on`() =
        runBlocking<Unit> {
            val none = h.listChains(amina).data()
            assertEquals(listOf(ChainStatus.AVAILABLE, ChainStatus.AVAILABLE), none.map { it.status })
            assertEquals("BASE-SEPOLIA", none.single { it.isDefault }.blockchain)
            assertEquals(listOf("USDC", "ETH"), none.first().assets)

            h.activeWallet(amina)
            h.add(amina, "SOL-DEVNET").data()
            val chains = h.listChains(amina).data().associate { it.blockchain to it.status }
            assertEquals(mapOf("BASE-SEPOLIA" to ChainStatus.ENABLED, "SOL-DEVNET" to ChainStatus.ADDING), chains)
        }

    @Test
    fun `W12 adds a chain with a challenge, and the new wallet appears in W1 once signed`() =
        runBlocking<Unit> {
            val circleUserId = h.activeWallet(amina)

            val addition = h.add(amina, "SOL-DEVNET").data()

            assertEquals(ChainStatus.ADDING, addition.status)
            assertNotNull(addition.challengeId)
            h.custody.sign(circleUserId, "SOL-DEVNET", address = "So1AbC")
            val wallet = h.wallet(amina).data()
            assertEquals(
                "So1AbC",
                wallet.chains
                    .last()
                    .wallet.address,
                "Solana addresses keep their case",
            )
            assertEquals(
                listOf("BASE-SEPOLIA", "SOL-DEVNET"),
                h.bus.published.map { it.decode<WalletCreated>().blockchain },
                "one WalletCreated per chain",
            )
        }

    @Test
    fun `W13 adds every supported chain the wallet isn't on, and nothing once it is on all of them`() =
        runBlocking<Unit> {
            val circleUserId = h.activeWallet(amina)

            val added = h.addMissingChains(amina).data()

            // MATIC-AMOY is retired from receiving in these chains, so it is never added.
            assertEquals(listOf("SOL-DEVNET"), added.map { it.blockchain })
            assertEquals(true, added.all { it.status == ChainStatus.ADDING && it.challengeId != null })
            h.custody.sign(circleUserId, "SOL-DEVNET", address = "So1AbC")
            h.wallet(amina).data()
            assertEquals(emptyList(), h.addMissingChains(amina).data(), "already on every chain")
        }

    @Test
    fun `W13 needs a wallet that is set up`() =
        runBlocking<Unit> {
            assertEquals("wallet.not_found", h.addMissingChains(amina).code())
        }

    @Test
    fun `W12 on a chain already on the wallet answers ENABLED with nothing to sign`() =
        runBlocking<Unit> {
            h.activeWallet(amina)
            val addition = h.add(amina, "BASE-SEPOLIA").data()
            assertEquals(ChainStatus.ENABLED, addition.status)
            assertNull(addition.challengeId)
            assertEquals(0, h.custody.count("addWallet"))
        }

    @Test
    fun `W12 asked again after the user signed finds the wallet at Circle instead of a second challenge`() =
        runBlocking<Unit> {
            val circleUserId = h.activeWallet(amina)
            h.add(amina, "SOL-DEVNET").data()
            h.custody.sign(circleUserId, "SOL-DEVNET")

            val again = h.add(amina, "SOL-DEVNET").data()

            assertEquals(ChainStatus.ENABLED, again.status)
            assertEquals(1, h.custody.count("addWallet"))
        }

    @Test
    fun `W12 reads Circle before every challenge, so a chain signed elsewhere is found without wal adding`() =
        runBlocking<Unit> {
            val circleUserId = h.activeWallet(amina)
            h.custody.sign(circleUserId, "SOL-DEVNET")
            assertTrue(h.adding.list(amina).isEmpty())

            val addition = h.add(amina, "SOL-DEVNET").data()

            assertEquals(ChainStatus.ENABLED, addition.status)
            assertEquals(0, h.custody.count("addWallet"))
        }

    @Test
    fun `W12 with Circle down asks for no challenge and answers custody unavailable`() =
        runBlocking<Unit> {
            h.activeWallet(amina)
            h.custody.down = true
            assertEquals("wallet.custody_unavailable", h.add(amina, "SOL-DEVNET").code())
            assertEquals(0, h.custody.count("addWallet"))
        }

    @Test
    fun `W12 adds of one chain within the hour ask Circle with one key, whatever the request's key`() =
        runBlocking<Unit> {
            val circleUserId = h.activeWallet(amina)
            h.custody.keys.clear()

            val first = h.add(amina, "SOL-DEVNET").data()
            val second = h.add(amina, "SOL-DEVNET").data()
            h.clock.advance(Duration.ofHours(1))
            h.add(amina, "SOL-DEVNET").data()

            assertEquals(3, h.custody.keys.size)
            assertEquals(h.custody.keys[0], h.custody.keys[1], "two adds at once share Circle's challenge")
            assertTrue(h.custody.keys[2] != h.custody.keys[0], "a new hour, a new challenge")
            assertEquals(ChainStatus.ADDING, first.status)
            assertEquals(ChainStatus.ADDING, second.status)
            val bob = UserId.new()
            h.activeWallet(bob)
            h.add(bob, "SOL-DEVNET").data()
            assertTrue(h.custody.keys.last() != h.custody.keys[2], "another user's add has its own key")
            assertNotNull(circleUserId)
        }

    @Test
    fun `W12 never adds a chain retired from receiving, and lists it only to a wallet already on it`() =
        runBlocking<Unit> {
            val circleUserId = h.activeWallet(amina)

            assertEquals("wallet.chain_unsupported", h.add(amina, "MATIC-AMOY").code())
            assertEquals(0, h.custody.count("addWallet"))
            assertTrue(h.listChains(amina).data().none { it.blockchain == "MATIC-AMOY" })

            h.custody.sign(circleUserId, "MATIC-AMOY")
            h.adding.add(amina, "MATIC-AMOY")
            val retired = h.listChains(amina).data().single { it.blockchain == "MATIC-AMOY" }
            assertEquals(ChainStatus.ENABLED, retired.status)
            assertEquals(false, retired.receiveEnabled)
            assertEquals(ChainStatus.ENABLED, h.add(amina, "MATIC-AMOY").data().status, "already on it: harmless")
            val shown =
                h
                    .wallet(amina)
                    .data()
                    .chains
                    .single { it.wallet.blockchain == "MATIC-AMOY" }
            assertEquals(false, shown.receiveEnabled, "W1 still reads it, marked as not receiving")
        }

    @Test
    fun `W12 refuses an unsupported chain, a user with no wallet, and a wallet still being set up`() =
        runBlocking<Unit> {
            assertEquals("wallet.chain_unsupported", h.add(amina, "AVAX-FUJI").code())
            assertEquals("wallet.not_found", h.add(amina, "SOL-DEVNET").code())
            h.create(amina).data()
            assertEquals("wallet.not_ready", h.add(amina, "SOL-DEVNET").code())
        }
}
