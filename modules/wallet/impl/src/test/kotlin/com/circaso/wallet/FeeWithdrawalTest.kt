package com.circaso.wallet

import com.circaso.integrations.api.TreasuryAccess
import com.circaso.integrations.api.TreasuryBalance
import com.circaso.integrations.api.TreasuryTransactionState
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.wallet.domain.models.FeeDestination
import com.circaso.wallet.domain.models.FeeStaff
import com.circaso.wallet.domain.models.FeeWithdrawal
import com.circaso.wallet.domain.models.WithdrawalOrder
import com.circaso.wallet.domain.models.WithdrawalState
import com.circaso.wallet.domain.models.WithdrawalState.CONFIRMED
import com.circaso.wallet.domain.models.WithdrawalState.FAILED
import com.circaso.wallet.domain.models.WithdrawalState.PENDING
import com.circaso.wallet.domain.models.WithdrawalState.SENT
import com.circaso.wallet.domain.support.FeeWithdrawals
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

/**
 * Destinations and withdrawals of collected fees (decision 2026-11-18): what is refused and why, and the one way a
 * withdrawal moves, `PENDING → SENT → CONFIRMED | FAILED`, never looking sent unless Circle took it.
 */
class FeeWithdrawalTest {
    private val world = FeeWalletWorld().apply { treasury.grant(Network.MAINNET, TreasuryAccess.FULL) }
    private val admin = FeeStaff(UUID.randomUUID(), "admin@circaso.com")
    private val treasuryAddress = "0x00000000000000000000000000000000000000Aa"
    private val usdcToken = "token-usdc-base"
    private val ethToken = "token-eth-base"

    init {
        runBlocking { world.setup.ensure(Network.MAINNET) }
        hold(usdc = "250000000", eth = "1000000000000000") // 250 USDC, 0.001 ETH
    }

    private val baseWallet: String get() =
        world.wallets.rows
            .getValue("BASE")
            .circleWalletId!!

    private fun hold(usdc: String, eth: String?) {
        world.treasury.held[baseWallet] =
            listOfNotNull(
                TreasuryBalance(usdcToken, "USDC", USDC_BASE_MAINNET.lowercase(), 6, false, BigInteger(usdc)),
                eth?.let { TreasuryBalance(ethToken, "ETH", "", 18, true, BigInteger(it)) },
            )
    }

    private suspend fun destination(
        address: String = treasuryAddress,
        chain: String = "BASE",
        ready: Boolean = true,
    ): FeeDestination {
        val added = world.destinations.add(Network.MAINNET, chain, address, "Treasury", admin).data()
        if (ready) world.clock.advance(Duration.ofHours(24))
        return added
    }

    private fun request(
        destination: UUID,
        amount: String = "100000000",
        asset: String = "USDC",
        key: String = UUID.randomUUID().toString(),
        chain: String = "BASE",
    ) = WithdrawalOrder(Network.MAINNET, chain, asset, BigInteger(amount), destination, "Monthly sweep", key)

    private suspend fun withdraw(
        destination: UUID,
        amount: String = "100000000",
        asset: String = "USDC",
        key: String = UUID.randomUUID().toString(),
    ) = world.withdrawals.request(request(destination, amount, asset, key), admin)

    private fun history(withdrawal: FeeWithdrawal) = world.withdrawalRows.history.getValue(withdrawal.id)

    private fun stored(withdrawal: FeeWithdrawal) = world.withdrawalRows.rows.getValue(withdrawal.id)

    // Destinations

    @Test
    fun `a destination is usable only once the delay has passed, to the second`() =
        runBlocking<Unit> {
            val added = destination(ready = false)

            assertEquals(added.createdAt.plus(Duration.ofHours(24)), added.usableAt(world.destinations.delay()))
            assertEquals("fees.destination_not_ready", withdraw(added.id).code())
            world.clock.advance(Duration.ofHours(24).minusSeconds(1))
            assertEquals("fees.destination_not_ready", withdraw(added.id).code())
            world.clock.advance(Duration.ofSeconds(1))
            assertEquals(SENT, withdraw(added.id).data().state)
            assertTrue(world.withdrawalRows.rows.size == 1, "the refused ones left no withdrawal behind")
        }

    @Test
    fun `the delay is the database's setting`() =
        runBlocking<Unit> {
            world.wallets.delayHours = 72
            val added = destination(ready = false)

            world.clock.advance(Duration.ofHours(71))
            assertEquals("fees.destination_not_ready", withdraw(added.id).code())
            world.clock.advance(Duration.ofHours(1))
            assertEquals(SENT, withdraw(added.id).data().state)
        }

    @Test
    fun `a destination is one valid address on a chain that carries fees, stored in lower case, once`() =
        runBlocking<Unit> {
            suspend fun add(
                chain: String,
                address: String,
                label: String = "Treasury",
                network: Network = Network.MAINNET,
            ) = world.destinations.add(network, chain, address, label, admin)

            assertEquals("fees.invalid", add("BASE", "not-an-address").code())
            assertEquals("fees.invalid", add("BASE", "0x1234").code())
            assertEquals(
                "fees.invalid",
                add("SOL", "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU").code(),
                "no fee on Solana",
            )
            assertEquals("fees.invalid", add("BASE-SEPOLIA", treasuryAddress).code(), "a testnet chain on mainnet")
            assertEquals("fees.invalid", add("OP", treasuryAddress).code(), "not a chain of ours")
            assertEquals("fees.invalid", add("BASE", treasuryAddress, label = " ").code())
            assertEquals("fees.invalid", add("BASE", treasuryAddress, label = "x".repeat(81)).code())
            world.fakeCustody.invalidAddresses += treasuryAddress
            assertEquals("fees.invalid", add("BASE", treasuryAddress).code(), "Circle says it isn't valid there")
            world.fakeCustody.invalidAddresses.clear()

            val added = add(" base ", " $treasuryAddress ", " Treasury ").data()

            assertEquals("BASE" to treasuryAddress.lowercase(), added.blockchain to added.address)
            assertEquals("Treasury", added.label)
            assertEquals(admin.email, added.createdByEmail)
            assertEquals("fees.destination_exists", add("BASE", treasuryAddress.uppercase().replace("0X", "0x")).code())
            assertNotNull(add("MATIC", treasuryAddress).data(), "the same address on another chain is its own entry")
        }

    @Test
    fun `one of our own fee wallets is never a destination, on any chain or network`() =
        runBlocking<Unit> {
            val own =
                world.wallets.rows
                    .getValue("BASE")
                    .address

            val sameChain = world.destinations.add(Network.MAINNET, "BASE", own, "Loop", admin)
            val otherChain =
                world.destinations.add(
                    Network.MAINNET,
                    "ETH",
                    own.uppercase().replace("0X", "0x"),
                    "Loop",
                    admin,
                )

            assertEquals("fees.destination_is_fee_wallet", sameChain.code())
            assertEquals("fees.destination_is_fee_wallet", otherChain.code())
            assertTrue(world.destinationRows.rows.isEmpty())
        }

    @Test
    fun `an address that becomes a fee wallet after it was added can't be withdrawn to`() =
        runBlocking<Unit> {
            val added = destination()
            world.settings.fees["ETH"] =
                world.settings.fees
                    .getValue("ETH")
                    .copy(feeAddress = treasuryAddress)

            assertEquals("fees.destination_is_fee_wallet", withdraw(added.id).code())
        }

    @Test
    fun `a removed destination is gone from the list and can't be used, and adding it again waits again`() =
        runBlocking<Unit> {
            val added = destination()

            assertEquals(
                added.id,
                world.destinations
                    .remove(added.id, admin)
                    .data()
                    .id,
            )
            assertEquals("fees.destination_not_found", world.destinations.remove(added.id, admin).code())
            assertTrue(world.destinations.list(Network.MAINNET).isEmpty())
            assertEquals("fees.destination_not_found", withdraw(added.id).code())

            val again = destination(ready = false)
            assertEquals("fees.destination_not_ready", withdraw(again.id).code())
        }

    // What a withdrawal is refused for

    @Test
    fun `a withdrawal names a destination on its own chain`() =
        runBlocking<Unit> {
            val onMatic = destination(chain = "MATIC")

            assertEquals("fees.destination_not_found", withdraw(onMatic.id).code())
            assertEquals("fees.destination_not_found", withdraw(UUID.randomUUID()).code())
        }

    @Test
    fun `more than the wallet holds is refused, counting withdrawals on their way`() =
        runBlocking<Unit> {
            val to = destination()

            assertEquals("fees.insufficient_balance", withdraw(to.id, amount = "250000001").code())
            assertEquals(SENT, withdraw(to.id, amount = "200000000").data().state)
            assertEquals("fees.insufficient_balance", withdraw(to.id, amount = "50000001").code(), "200 are promised")
            assertEquals(SENT, withdraw(to.id, amount = "50000000").data().state)
            assertEquals(2, world.treasury.sends.size)
        }

    @Test
    fun `a zero amount, another network's chain and a coin that isn't offered are refused`() =
        runBlocking<Unit> {
            val to = destination()

            assertEquals("fees.invalid", withdraw(to.id, amount = "0").code())
            assertEquals(
                "fees.invalid",
                world.withdrawals.request(request(to.id, chain = "BASE-SEPOLIA"), admin).code(),
            )
            assertEquals("fees.asset_unsupported", withdraw(to.id, asset = "SHIB").code())
            assertTrue(world.treasury.sends.isEmpty())
        }

    @Test
    fun `a token that only calls itself USDC is not the USDC withdrawn`() =
        runBlocking<Unit> {
            val to = destination()
            world.treasury.held[baseWallet] =
                listOf(
                    TreasuryBalance(
                        "token-fake",
                        "USDC",
                        "0x00000000000000000000000000000000000bad00",
                        6,
                        false,
                        BigInteger("999000000"),
                    ),
                    TreasuryBalance(ethToken, "ETH", "", 18, true, BigInteger("1000000000000000")),
                )

            assertEquals("fees.insufficient_balance", withdraw(to.id, amount = "1000000").code())
            assertTrue(world.treasury.sends.isEmpty())
        }

    @Test
    fun `the wallet pays its own network fee - without enough of the chain's coin nothing is sent`() =
        runBlocking<Unit> {
            val to = destination()
            world.treasury.estimate = Resource.success(BigDecimal("0.0000200000000000001"))

            hold(usdc = "250000000", eth = "20000000000000") // 0.00002 ETH: a hair under the estimate
            assertEquals("fees.insufficient_gas", withdraw(to.id).code())
            hold(usdc = "250000000", eth = null)
            assertEquals("fees.insufficient_gas", withdraw(to.id).code(), "no native coin at all")
            hold(usdc = "250000000", eth = "20000000000001") // the estimate rounded up to the next wei
            assertEquals(SENT, withdraw(to.id).data().state)
            assertEquals(1, world.treasury.sends.size)
        }

    @Test
    fun `when Circle won't estimate, the chain's floor stands in, and when Circle is down nothing is decided`() =
        runBlocking<Unit> {
            val to = destination()
            world.treasury.estimate = Resource.error(FakeTreasury.REFUSED)

            hold(usdc = "250000000", eth = "199999999999999") // just under the rollup floor of 0.0002 ETH
            assertEquals("fees.insufficient_gas", withdraw(to.id).code())
            hold(usdc = "250000000", eth = "200000000000000")
            assertEquals(SENT, withdraw(to.id).data().state)

            world.treasury.estimate = Resource.error(FakeTreasury.DOWN)
            assertEquals("fees.custody_unavailable", withdraw(to.id).code())
            assertEquals(1, world.withdrawalRows.rows.size)
        }

    @Test
    fun `withdrawing the chain's own coin leaves enough of it for the fee`() =
        runBlocking<Unit> {
            val to = destination()
            world.treasury.estimate = Resource.success(BigDecimal("0.0001"))

            assertEquals(
                "fees.insufficient_balance",
                withdraw(to.id, amount = "1000000000000001", asset = "ETH").code(),
            )
            assertEquals("fees.insufficient_gas", withdraw(to.id, amount = "950000000000000", asset = "ETH").code())
            val sent = withdraw(to.id, amount = "900000000000000", asset = "eth").data()

            assertEquals(SENT, sent.state)
            assertEquals("ETH" to 18, sent.asset to sent.decimals)
            assertEquals(
                ethToken,
                world.treasury.sends
                    .single()
                    .tokenId,
            )
        }

    @Test
    fun `without the entity secret, or without a Circle wallet behind the address, nothing can be withdrawn`() =
        runBlocking<Unit> {
            val to = destination()
            world.treasury.grant(Network.MAINNET, TreasuryAccess.READ_ONLY)

            val noSecret = withdraw(to.id) as Resource.Error

            assertEquals("fees.withdrawals_unavailable", noSecret.error.code)
            assertTrue("CIRCLE_MAINNET_ENTITY_SECRET" in noSecret.error.message, noSecret.error.message)

            world.treasury.grant(Network.MAINNET, TreasuryAccess.FULL)
            world.wallets.rows.compute("BASE") { _, row -> row!!.copy(circleWalletId = null) }
            assertEquals("fees.withdrawals_unavailable", withdraw(to.id).code())
            assertEquals(
                "fees.withdrawals_unavailable",
                FeeWithdrawals(
                    null,
                    world.withdrawalRows,
                    world.destinationRows,
                    world.wallets,
                    world.balances,
                    world.destinations,
                    world.clock,
                ).request(request(to.id), admin).code(),
                "the app's processes have no treasury at all",
            )
            assertTrue(world.treasury.sends.isEmpty() && world.withdrawalRows.rows.isEmpty())
        }

    // The life of a withdrawal

    @Test
    fun `the only moves are pending to sent or failed, and sent to confirmed or failed`() {
        val allowed = setOf(PENDING to SENT, PENDING to FAILED, SENT to CONFIRMED, SENT to FAILED)

        for (from in WithdrawalState.entries) {
            for (to in WithdrawalState.entries) {
                assertEquals((from to to) in allowed, from.mayBecome(to), "$from -> $to")
            }
        }
        assertEquals(listOf(true, true, false, false), WithdrawalState.entries.map { it.isOpen })
    }

    @Test
    fun `Circle takes it - sent with Circle's transaction, exact to the minor unit, then confirmed with its hash`() =
        runBlocking<Unit> {
            val to = destination()

            val sent = withdraw(to.id, amount = "123456789").data()

            assertEquals(SENT, sent.state)
            assertEquals("tx-1", sent.circleTxId)
            assertNull(sent.txHash)
            assertEquals(BigInteger("123456789"), sent.amountMinor)
            assertEquals(
                Triple("USDC", 6, USDC_BASE_MAINNET.lowercase()),
                Triple(sent.asset, sent.decimals, sent.tokenAddress),
            )
            assertEquals(to.address to "Treasury", sent.destinationAddress to sent.destinationLabel)
            assertEquals(admin.email to "Monthly sweep", sent.requestedByEmail to sent.reason)
            val asked = world.treasury.sends.single()
            assertEquals(BigInteger("123456789"), asked.amountMinor)
            assertEquals(usdcToken, asked.tokenId)
            assertEquals(baseWallet, asked.walletId)
            assertEquals(to.address, asked.destinationAddress)
            assertEquals(sent.circleKey, asked.idempotencyKey)
            assertEquals("circaso-fee-withdrawal:${sent.id}", asked.refId)

            assertEquals(0, world.withdrawals.follow(), "still on its way")
            world.treasury.settle("tx-1", TreasuryTransactionState.IN_FLIGHT, txHash = "0xabc")
            assertEquals(0, world.withdrawals.follow())
            assertEquals("0xabc", stored(sent).txHash, "the hash is kept as soon as Circle knows it")
            world.treasury.settle("tx-1", TreasuryTransactionState.CONFIRMED, txHash = "0xabc")
            assertEquals(1, world.withdrawals.follow())

            assertEquals(CONFIRMED to "0xabc", stored(sent).let { it.state to it.txHash })
            assertEquals(listOf(PENDING, SENT, CONFIRMED), history(sent))
            assertEquals(0, world.withdrawals.follow(), "a finished withdrawal is left alone")
            world.treasury.settle("tx-1", TreasuryTransactionState.FAILED)
            world.withdrawals.follow()
            assertEquals(CONFIRMED, stored(sent).state, "and never changes")
        }

    @Test
    fun `Circle refuses - failed at once, staff are told, and nothing looks sent`() =
        runBlocking<Unit> {
            val to = destination()
            world.treasury.failing["send"] = FakeTreasury.REFUSED

            val refused = withdraw(to.id) as Resource.Error

            assertEquals("fees.withdrawal_refused", refused.error.code)
            assertTrue("HTTP 400, code 156004" in refused.error.message, refused.error.message)
            val row =
                world.withdrawalRows.rows.values
                    .single()
            assertEquals(FAILED, row.state)
            assertNull(row.circleTxId)
            assertTrue("HTTP 400" in row.failureReason.orEmpty())
            assertEquals(listOf(PENDING, FAILED), history(row))
            assertEquals(0, world.withdrawals.follow(), "a failed one isn't tried again")
            world.treasury.failing.clear()
            assertEquals(SENT, withdraw(to.id, amount = "250000000").data().state, "and it holds no balance back")
        }

    @Test
    fun `Circle can't be reached - it stays pending, and is sent once, later, with the same key`() =
        runBlocking<Unit> {
            val to = destination()
            world.treasury.failing["send"] = FakeTreasury.DOWN

            val pending = withdraw(to.id).data()

            assertEquals(PENDING, pending.state, "Circle may have it: it is neither sent nor failed")
            assertNull(pending.circleTxId)
            assertEquals(0, world.withdrawals.follow(), "not within the two minutes the request itself may take")
            world.clock.advance(Duration.ofMinutes(2))
            assertEquals(0, world.withdrawals.follow(), "still down")
            assertEquals(PENDING, stored(pending).state)

            world.treasury.failing.clear()
            assertEquals(1, world.withdrawals.follow())

            assertEquals(SENT to "tx-1", stored(pending).let { it.state to it.circleTxId })
            assertEquals(
                setOf(pending.circleKey),
                world.treasury.sends
                    .map { it.idempotencyKey }
                    .toSet(),
            )
            assertEquals(listOf(PENDING, SENT), history(pending))
        }

    @Test
    fun `an answer lost after Circle took it sends nothing twice`() =
        runBlocking<Unit> {
            val to = destination()
            val first = withdraw(to.id).data()
            // As if the answer never arrived: the row is pending again, and the job asks with the same key.
            world.withdrawalRows.rows[first.id] = first.copy(state = PENDING, circleTxId = null)
            world.clock.advance(Duration.ofMinutes(2))

            world.withdrawals.follow()

            assertEquals(SENT to "tx-1", stored(first).let { it.state to it.circleTxId })
            assertEquals(1, world.treasury.transactions.size, "Circle made one transaction")
        }

    @Test
    fun `a pending withdrawal Circle then refuses fails, and one the chain fails says why`() =
        runBlocking<Unit> {
            val to = destination()
            world.treasury.failing["send"] = FakeTreasury.DOWN
            val pending = withdraw(to.id).data()
            world.treasury.failing["send"] = FakeTreasury.REFUSED
            world.clock.advance(Duration.ofMinutes(2))

            assertEquals(1, world.withdrawals.follow())
            assertEquals(listOf(PENDING, FAILED), history(pending))

            world.treasury.failing.clear()
            val sent = withdraw(to.id).data()
            world.treasury.settle(
                sent.circleTxId!!,
                TreasuryTransactionState.FAILED,
                failureReason = "INSUFFICIENT_NATIVE_TOKEN",
            )
            assertEquals(1, world.withdrawals.follow())

            assertEquals(FAILED to "INSUFFICIENT_NATIVE_TOKEN", stored(sent).let { it.state to it.failureReason })
            assertEquals(listOf(PENDING, SENT, FAILED), history(sent))
        }

    @Test
    fun `the same Idempotency-Key from the same person is the same withdrawal`() =
        runBlocking<Unit> {
            val to = destination()

            val first = withdraw(to.id, key = "key-1").data()
            val again = withdraw(to.id, amount = "5", key = "key-1").data()
            val other = FeeStaff(UUID.randomUUID(), "other@circaso.com")
            val theirs = world.withdrawals.request(request(to.id, key = "key-1"), other).data()

            assertEquals(first.id, again.id)
            assertEquals(BigInteger("100000000"), again.amountMinor)
            assertFalse(theirs.id == first.id, "another person's key is their own")
            assertEquals(2, world.treasury.sends.size)
        }

    @Test
    fun `the list is newest first and by network`() =
        runBlocking<Unit> {
            val to = destination()
            val first = withdraw(to.id, amount = "1").data()
            world.clock.advance(Duration.ofSeconds(1))
            val second = withdraw(to.id, amount = "2").data()

            assertEquals(listOf(second.id, first.id), world.withdrawals.page(Network.MAINNET, null, 10).map { it.id })
            assertEquals(listOf(first.id), world.withdrawals.page(null, second.id, 10).map { it.id })
            assertTrue(world.withdrawals.page(Network.TESTNET, null, 10).isEmpty())
        }
}
