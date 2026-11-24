package com.circaso.ramp

import com.circaso.integrations.api.CollectionCredentials
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.TreasuryTransactionState
import com.circaso.ramp.data.support.Eip55Addresses
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.DestinationCommand
import com.circaso.ramp.domain.models.PaymentAccountCommand
import com.circaso.ramp.domain.models.WithdrawCommand
import com.circaso.ramp.domain.models.Withdrawal
import com.circaso.ramp.domain.models.WithdrawalDestination
import com.circaso.ramp.domain.models.WithdrawalState
import com.circaso.ramp.domain.ports.AddressCheck
import com.circaso.ramp.domain.support.WithdrawalExplorer
import com.circaso.ramp.domain.support.WithdrawalSends
import com.circaso.shared.DomainError
import com.circaso.shared.Resource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Merchant float withdrawals (2026-11-24): addresses saved with the PIN and usable only after the cooling-off delay;
 * only free float, reserved at once under the merchant's lock; sent once by the ops process with a key from the
 * withdrawal, and its reservation consumed on confirmation or released on a definitive failure.
 */
class WithdrawalsTest {
    private val ramp = Harness()
    private val crynce = ramp.crynce.merchantId
    private val pin = "482913"
    private val address = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"

    private fun <T> Resource<T>.error(): DomainError = (this as Resource.Error).error

    private fun <T> Resource<T>.data(): T = (this as Resource.Success).data

    private suspend fun withPin() {
        val account = PaymentAccountCommand(CollectionCredentials("Crynce-user", "Crynce-pass", "911"), pin)
        assertIs<Resource.Success<*>>(ramp.storePayments(crynce, TESTNET, account))
    }

    private suspend fun destination(
        raw: String = address,
        label: String = "Ledger",
        code: String = pin,
    ): Resource<WithdrawalDestination> =
        when (val saved = ramp.floatWithdrawals.addDestination(crynce, TESTNET, DestinationCommand(raw, label, code))) {
            is Resource.Error -> saved
            is Resource.Success -> Resource.success(saved.data.destination)
        }

    /** A saved address past its delay. */
    private suspend fun usable(): WithdrawalDestination {
        val saved = destination().data()
        ramp.clock.advance(24 * 3_600)
        return saved
    }

    private suspend fun withdraw(
        amountMinor: Long,
        to: WithdrawalDestination,
        key: String? = "k-$amountMinor",
        code: String = pin,
    ): Resource<Withdrawal> =
        ramp.floatWithdrawals.withdraw(crynce, TESTNET, WithdrawCommand(amountMinor, to.destinationId, code, key))

    private suspend fun free(): Long = ramp.views.floatOf(ramp.walletOf(ramp.crynce))!!.freeMinor

    // ---- addresses ----

    @Test
    fun `an address is checked, checksummed, never the float's own, and usable only after the delay`() =
        runTest {
            withPin()

            assertEquals("ramp.address_invalid", destination("0x1234").error().code)
            assertEquals("ramp.address_invalid", destination("5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed").error().code)
            val typo = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAeD"
            assertEquals("ramp.address_invalid", destination(typo).error().code, "a mixed-case typo fails the checksum")
            assertEquals("ramp.address_invalid", destination("0x" + "0".repeat(40)).error().code)
            val own = ramp.walletOf(ramp.crynce).address
            ramp.merchantWallets.rows[crynce to TESTNET] =
                ramp.walletOf(ramp.crynce).copy(address = "0xab5801a7d398351b8be11c439e05c5b3259aec9b")
            assertEquals(
                RampError.DestinationOwnFloat,
                destination("0xAB5801A7D398351B8BE11C439E05C5B3259AEC9B").error(),
                "the float's own address, in any case",
            )
            assertTrue(own.isNotEmpty())

            val saved = destination(address.lowercase(), "Ledger").data()
            assertEquals(address, saved.address, "kept in its checksummed form")
            assertEquals(ramp.clock.instant().plusSeconds(24 * 3_600), saved.usableAt)
            assertEquals(RampError.DestinationExists, destination(address.uppercase().replace("0X", "0x")).error())
            val book = ramp.floatWithdrawals.book(crynce, TESTNET)
            assertEquals(listOf(saved) to 24, book.destinations to book.delayHours)
        }

    @Test
    fun `saving and removing an address need the PIN, and a wrong one counts toward the lock`() =
        runTest {
            withPin()

            assertEquals(RampError.PinInvalid, destination(code = "000000").error())
            val saved = destination().data()
            val removing = ramp.floatWithdrawals.removeDestination(crynce, TESTNET, saved.destinationId, "111111")
            assertEquals(RampError.PinInvalid, removing.error())
            assertIs<Resource.Success<Unit>>(
                ramp.floatWithdrawals.removeDestination(crynce, TESTNET, saved.destinationId, pin),
            )
            assertTrue(
                ramp.floatWithdrawals
                    .book(crynce, TESTNET)
                    .destinations
                    .isEmpty(),
            )
            assertEquals(
                RampError.DestinationNotFound,
                ramp.floatWithdrawals.removeDestination(crynce, TESTNET, saved.destinationId, pin).error(),
            )
        }

    @Test
    fun `a merchant without a PIN can't save an address`() =
        runTest {
            assertEquals(RampError.MerchantSetupIncomplete, destination().error())
        }

    // ---- withdrawing ----

    @Test
    fun `an address still cooling off can't be withdrawn to`() =
        runTest {
            withPin()
            val saved = destination().data()
            ramp.clock.advance(23 * 3_600)

            assertEquals("ramp.destination_not_ready", withdraw(5_000_000, saved).error().code)
            ramp.clock.advance(3_600)
            assertIs<Resource.Success<Withdrawal>>(withdraw(5_000_000, saved))
        }

    @Test
    fun `only the free float can be withdrawn, and the amount is reserved at once`() =
        runTest {
            withPin()
            val to = usable()
            assertEquals(100_000_000, free())

            assertEquals("ramp.amount_invalid", withdraw(999_999, to).error().code, "under 1 USDC")
            assertEquals("ramp.insufficient_float", withdraw(100_000_001, to).error().code)
            val taken = withdraw(60_000_000, to).data()
            assertEquals(WithdrawalState.REQUESTED, taken.state)
            assertEquals(40_000_000, free(), "routing sees less free float at once")
            val float = ramp.views.floatOf(ramp.walletOf(ramp.crynce))!!
            assertEquals(60_000_000, float.withdrawingMinor)
            assertEquals(
                "ramp.insufficient_float",
                withdraw(40_000_001, to).error().code,
                "the open withdrawal holds its part",
            )
            assertNull(ramp.treasury.sends.firstOrNull(), "the app's process never sends")
        }

    @Test
    fun `an open order's float and Circaso's unswept share are never withdrawn`() =
        runTest {
            withPin()
            val to = usable()
            // A day passed for the address: the merchant's trading window is opened again.
            ramp.merchants.rows[crynce] =
                ramp.merchants.rows
                    .getValue(crynce)
                    .copy(onlineUntil = ramp.clock.instant().plusSeconds(8 * 3_600))
            ramp.completed(ramp.amina, ramp.phone)
            val owed = ramp.ledger.owedTotal(crynce, TESTNET)
            assertTrue(owed > 0, "Circaso's share is held in the float")
            val buyer = ramp.user("+254711000222")
            ramp.prompted(
                buyer,
                com.circaso.shared.PhoneNumber
                    .parse("+254711000222")!!,
            )
            val balance = ramp.treasury.balances.getValue(ramp.walletOf(ramp.crynce).custodyWalletId)
            val reserved = ramp.reservations.openTotal(crynce, TESTNET)
            val freeNow = balance - reserved - owed

            assertEquals(freeNow, free())
            assertEquals("ramp.insufficient_float", withdraw(freeNow + 1, to).error().code)
            assertIs<Resource.Success<Withdrawal>>(withdraw(freeNow, to, key = "all-of-it"))
            assertEquals(0, free())
        }

    @Test
    fun `the same idempotency key is the same withdrawal, reserved once`() =
        runTest {
            withPin()
            val to = usable()

            val first = withdraw(5_000_000, to, key = "same").data()
            val again = withdraw(5_000_000, to, key = "same").data()

            assertEquals(first.withdrawalId, again.withdrawalId)
            assertEquals(1, ramp.withdrawalRows.rows.size)
            assertEquals(95_000_000, free())
        }

    @Test
    fun `the daily cap counts today's withdrawals, failed ones left out`() =
        runTest {
            withPin()
            ramp.settingsRows.set(TESTNET) { it.copy(withdrawDailyMaxMinor = 10_000_000) }
            ramp.book.invalidate()
            val to = usable()

            withdraw(6_000_000, to).data()
            assertEquals("ramp.limit_exceeded", withdraw(5_000_000, to).error().code)
            assertIs<Resource.Success<Withdrawal>>(withdraw(4_000_000, to))
            val page = ramp.floatWithdrawals.page(crynce, TESTNET, 20, null)
            assertEquals(10_000_000L to 10_000_000L, page.usedTodayMinor to page.dailyMaxMinor)
        }

    @Test
    fun `withdrawing is allowed offline, and needs the PIN`() =
        runTest {
            withPin()
            val to = usable()
            ramp.goOffline(crynce)

            assertEquals(RampError.PinInvalid, withdraw(5_000_000, to, code = "000000").error())
            assertIs<Resource.Success<Withdrawal>>(withdraw(5_000_000, to))
        }

    // ---- sending (the ops process) ----

    @Test
    fun `the ops job sends once with a key from the withdrawal, then completes it and consumes the hold`() =
        runTest {
            withPin()
            val to = usable()
            val asked = withdraw(25_000_000, to).data()

            ramp.release()
            ramp.release()

            val sent = ramp.treasury.sends.single()
            assertEquals(WithdrawalSends.withdrawalKey(asked.withdrawalId), sent.idempotencyKey)
            assertEquals(address to 25_000_000.toBigInteger(), sent.destinationAddress to sent.amountMinor)
            assertEquals(asked.withdrawalId.toString(), sent.refId)
            assertEquals(
                WithdrawalState.SENT,
                ramp.withdrawalRows.rows
                    .getValue(asked.withdrawalId)
                    .state,
            )

            ramp.treasury.settleAll()
            ramp.release()

            val done = ramp.floatWithdrawals.one(crynce, asked.withdrawalId).data()
            assertEquals(WithdrawalState.COMPLETED, done.state)
            assertEquals("0xhash-tx-1", done.txHash)
            assertEquals(0, ramp.reservations.withdrawingTotal(crynce, TESTNET))
            assertEquals(75_000_000, free(), "the balance fell by the amount and the hold is gone")
        }

    @Test
    fun `while Circle can't be reached the withdrawal waits and is asked again with the same key`() =
        runTest {
            withPin()
            val to = usable()
            val asked = withdraw(5_000_000, to).data()

            ramp.treasury.failure = IntegrationError.Unavailable("circle")
            ramp.release()
            assertEquals(
                WithdrawalState.REQUESTED,
                ramp.withdrawalRows.rows
                    .getValue(asked.withdrawalId)
                    .state,
            )

            ramp.treasury.failure = null
            ramp.release()
            assertEquals(
                WithdrawalState.SENT,
                ramp.withdrawalRows.rows
                    .getValue(asked.withdrawalId)
                    .state,
            )
            assertEquals(1, ramp.treasury.sends.size)
        }

    @Test
    fun `a refusal or a failure on chain fails it and gives the amount back to the free float`() =
        runTest {
            withPin()
            val to = usable()
            val refused = withdraw(5_000_000, to, key = "a").data()
            // The float fell under the amount after it was asked for: Circle refuses the transfer.
            val walletId = ramp.walletOf(ramp.crynce).custodyWalletId
            ramp.treasury.balances[walletId] = 1_000_000
            ramp.release()
            ramp.treasury.balances[walletId] = 100_000_000

            val failed = ramp.withdrawalRows.rows.getValue(refused.withdrawalId)
            assertEquals(WithdrawalState.FAILED to "refused", failed.state to failed.failureReason)
            assertEquals(100_000_000, free())

            val onChain = withdraw(7_000_000, to, key = "b").data()
            ramp.release()
            ramp.treasury.settle("tx-1", TreasuryTransactionState.FAILED)
            ramp.treasury.balances[ramp.walletOf(ramp.crynce).custodyWalletId] = 100_000_000
            ramp.release()

            val second = ramp.withdrawalRows.rows.getValue(onChain.withdrawalId)
            assertEquals(WithdrawalState.FAILED to "failed_on_chain", second.state to second.failureReason)
            assertEquals(100_000_000, free())
            assertEquals(1, ramp.treasury.sends.size, "never sent again after a failure")
            assertTrue(ramp.alerts.stuck.isNotEmpty())
        }

    @Test
    fun `staff see the withdrawals and every address, removed ones too`() =
        runTest {
            withPin()
            val to = usable()
            withdraw(5_000_000, to).data()
            val other = destination("0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359", "Old").data()
            ramp.floatWithdrawals.removeDestination(crynce, TESTNET, other.destinationId, pin)

            val seen = ramp.staffWithdrawals(crynce, TESTNET, 50).data()
            assertEquals(1, seen.withdrawals.size)
            assertEquals(2, seen.destinations.size)
            assertEquals(
                RampError.MerchantNotFound,
                ramp
                    .staffWithdrawals(
                        com.circaso.ramp.api.MerchantId
                            .new(),
                        TESTNET,
                        50,
                    ).error(),
            )
        }

    // ---- small pieces ----

    @Test
    fun `EIP-55 checksums match the standard's vectors`() {
        val vectors =
            listOf(
                "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
                "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
                "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
                "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb",
            )
        vectors.forEach {
            assertEquals(it, Eip55Addresses.checksummed(it.removePrefix("0x").lowercase()))
            assertEquals(AddressCheck.Valid(it), Eip55Addresses().check(it))
        }
    }

    @Test
    fun `explorer links follow the chain`() {
        assertEquals("https://basescan.org/tx/0xab", WithdrawalExplorer.url("BASE", "0xab"))
        assertEquals("https://sepolia.basescan.org/tx/0xab", WithdrawalExplorer.url("BASE-SEPOLIA", "0xab"))
        assertNull(WithdrawalExplorer.url("BASE", null))
    }

    @Test
    fun `staff settings for withdrawals are checked`() =
        runTest {
            val current = ramp.getSettings(TESTNET).data()
            val tooLong = current.copy(withdrawDestinationDelayHours = 721)
            assertEquals("ramp.settings_invalid", ramp.updateSettings(tooLong, current.version, "staff").error().code)
            val capUnderMin = current.copy(withdrawDailyMaxMinor = 500_000)
            assertEquals(
                "ramp.settings_invalid",
                ramp.updateSettings(capUnderMin, current.version, "staff").error().code,
            )
            val fine = current.copy(withdrawDestinationDelayHours = 48, withdrawMinMinor = 2_000_000)
            val saved = ramp.updateSettings(fine, current.version, "staff").data().after
            assertEquals(48 to 2_000_000L, saved.withdrawDestinationDelayHours to saved.withdrawMinMinor)
        }
}
