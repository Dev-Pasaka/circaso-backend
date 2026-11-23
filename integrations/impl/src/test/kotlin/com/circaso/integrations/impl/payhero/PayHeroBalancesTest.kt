package com.circaso.integrations.impl.payhero

import com.circaso.integrations.api.BalanceReading
import com.circaso.integrations.api.CollectionCredentials
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.ProviderBalances
import com.circaso.integrations.impl.payhero.FakePayHero.Companion.API
import com.circaso.integrations.impl.payhero.FakePayHero.Companion.AUTH
import com.circaso.integrations.impl.payhero.FakePayHero.Companion.LEGACY
import com.circaso.integrations.impl.payhero.FakePayHero.Companion.WALLETS
import com.circaso.shared.FiatAmount
import com.circaso.shared.Resource
import kotlinx.coroutines.runBlocking
import java.net.ConnectException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PayHeroBalancesTest {
    private val vendor = FakePayHero()
    private val payHero = PayHeroCollections(vendor.hosts)
    private val merchant = CollectionCredentials("merchant-user", "merchant-pass", "911")

    private val serviceWallet = "$LEGACY$WALLETS?wallet_type=service_wallet"
    private val accountBalance = "$API/api/global/accounts/63/balance"
    private val account = "$AUTH/api/v2/account/63"

    private fun balances(accountId: String? = "63"): Resource<ProviderBalances> =
        runBlocking { payHero.balances(merchant, accountId) }

    private fun read(accountId: String? = "63"): ProviderBalances = (balances(accountId) as Resource.Success).data

    private fun kes(minor: Long, source: String) = BalanceReading(FiatAmount(minor, "KES"), source)

    private val finoqBoth =
        """{"service_wallet_balance":{"account_id":63,"balance":68,"currency":"KES"},""" +
            """"wallet_balance":{"account_id":63,"balance":250,"currency":"KES"}}"""

    @Test
    fun `the three wallets are each read from their own call, with the call that gave it`() {
        vendor.answer(
            serviceWallet,
            """{"id":7,"wallet_type":"service_wallet","currency":"KES","available_balance":49.5}""",
        )
        vendor.answer(accountBalance, finoqBoth)

        val read = read()

        assertEquals(kes(4_950, PayHeroBalances.SERVICE_WALLET), read.collectionFees, "PayHero's: buying's fees")
        assertEquals(kes(6_800, PayHeroBalances.ACCOUNT_SERVICE), read.payoutFees, "FinoQ's: selling's fees")
        assertEquals(kes(25_000, PayHeroBalances.ACCOUNT_WALLET), read.payout)
        assertEquals(listOf(serviceWallet, accountBalance), vendor.asked, "the guessed payment_wallet query is gone")
    }

    @Test
    fun `one service wallet is never reported as the other`() {
        vendor.answer(serviceWallet, "", 404)
        vendor.answer(accountBalance, finoqBoth)
        val noPayHero = read()
        assertNull(noPayHero.collectionFees, "PayHero's wallet unread stays unknown, never FinoQ's number")
        assertEquals(kes(6_800, PayHeroBalances.ACCOUNT_SERVICE), noPayHero.payoutFees)

        vendor.answer(serviceWallet, """{"wallet_type":"service_wallet","available_balance":49.5}""")
        vendor.answer(accountBalance, """{"wallet_balance":{"account_id":63,"balance":250,"currency":"KES"}}""")
        vendor.answer(account, "", 404)
        val noFinoq = read()
        assertEquals(kes(4_950, PayHeroBalances.SERVICE_WALLET), noFinoq.collectionFees)
        assertNull(noFinoq.payoutFees, "FinoQ's wallet unread stays unknown, never PayHero's number")
    }

    @Test
    fun `without an account id only PayHero's service wallet is asked`() {
        vendor.answer(serviceWallet, """{"wallet_type":"service_wallet","available_balance":49.5}""")

        val read = read(accountId = null)

        assertEquals(ProviderBalances(collectionFees = kes(4_950, PayHeroBalances.SERVICE_WALLET)), read)
        assertEquals(listOf(serviceWallet), vendor.asked)
    }

    @Test
    fun `the new account balance answers the payout balance, under every name seen for one`() {
        vendor.answer(serviceWallet, """{"available_balance":20}""")
        val layouts =
            mapOf(
                """{"available_balance":1200.5,"currency":"KES"}""" to 120_050L,
                """{"balance":"1200"}""" to 120_000L,
                """{"success":true,"data":{"available":1200,"currency":"KES"}}""" to 120_000L,
                """{"account":{"account_balance":0}}""" to 0L,
                """{"wallet":{"payment_wallet_balance":"0.50"}}""" to 50L,
            )

        for ((body, minor) in layouts) {
            vendor.answer(accountBalance, body)
            assertEquals(kes(minor, PayHeroBalances.ACCOUNT_BALANCE), read().payout, body)
        }
        assertEquals(kes(2_000, PayHeroBalances.SERVICE_WALLET), read().collectionFees)
    }

    @Test
    fun `FinoQ's real two-wallet answer gives both its wallets, an empty payment wallet reading as zero`() {
        vendor.answer(serviceWallet, "", 404)
        vendor.answer(
            accountBalance,
            """{"service_wallet_balance":{"account_id":63,"balance":68,"currency":"KES"},""" +
                """"wallet_balance":{"account_id":63,"currency":"KES"}}""",
        )
        assertEquals(
            ProviderBalances(
                payoutFees = kes(6_800, PayHeroBalances.ACCOUNT_SERVICE),
                payout = kes(0, PayHeroBalances.ACCOUNT_WALLET),
            ),
            read(),
            "the wallet FinoQ sends without a balance is empty, as its withdraw said",
        )

        vendor.answer(accountBalance, """{"service_wallet_balance":{"account_id":63,"balance":68,"currency":"KES"}}""")
        assertNull(read().payout, "no wallet object at all stays unknown, never zero")
    }

    @Test
    fun `FinoQ's service wallet falls back to the Team on the auth host`() {
        vendor.answer(accountBalance, """{"balance":300,"service_wallet_balance":12}""")
        assertEquals(
            ProviderBalances(
                payoutFees = kes(1_200, PayHeroBalances.ACCOUNT_SERVICE),
                payout = kes(30_000, PayHeroBalances.ACCOUNT_BALANCE),
            ),
            read(),
            "the new balance answer carried both",
        )

        vendor.answer(accountBalance, """{"balance":300}""")
        vendor.answer(account, """{"account":{"id":63,"currency":"KES","service_wallet_balance":7.25}}""")
        assertEquals(kes(725, PayHeroBalances.ACCOUNT_SERVICE_WALLET), read().payoutFees)
        assertEquals(account, vendor.asked.last())
    }

    @Test
    fun `a balance that can't be read is null, never zero`() {
        vendor.answer(serviceWallet, """{"wallet_type":"service_wallet"}""")
        vendor.answer(accountBalance, """{"balance":{"available":10}}""")
        vendor.answer(account, "<html>nope</html>")

        assertEquals(ProviderBalances(), read(), "answered, but nothing this client can read")

        vendor.answer(accountBalance, """{"balance":"12"}""")
        vendor.fail(serviceWallet) { ConnectException("refused") }
        assertEquals(ProviderBalances(payout = kes(1_200, PayHeroBalances.ACCOUNT_BALANCE)), read())
    }

    @Test
    fun `with nothing read, refused credentials and an unreachable provider are errors`() {
        listOf(serviceWallet, accountBalance, account).forEach { vendor.answer(it, "", 401) }
        assertIs<IntegrationError.Rejected>((balances() as Resource.Error).error)

        listOf(serviceWallet, accountBalance, account).forEach { where ->
            vendor.fail(where) { ConnectException("refused") }
        }
        assertIs<IntegrationError.Unavailable>((balances() as Resource.Error).error)
    }

    @Test
    fun `the on-ramp's fee balance is unchanged`() {
        vendor.answer(serviceWallet, """{"wallet_type":"service_wallet","available_balance":"120"}""")

        val balance = runBlocking { payHero.balance(merchant) as Resource.Success }.data

        assertEquals(FiatAmount(12_000, "KES"), balance.fees)
        assertEquals(listOf(serviceWallet), vendor.asked)
    }
}
