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
    private val paymentWallet = "$LEGACY$WALLETS?wallet_type=payment_wallet"
    private val accountBalance = "$API/api/global/accounts/63/balance"
    private val account = "$AUTH/api/v2/account/63"

    private fun balances(accountId: String? = "63"): Resource<ProviderBalances> =
        runBlocking { payHero.balances(merchant, accountId) }

    private fun read(accountId: String? = "63"): ProviderBalances = (balances(accountId) as Resource.Success).data

    private fun kes(minor: Long, source: String) = BalanceReading(FiatAmount(minor, "KES"), source)

    @Test
    fun `both wallets are read from the legacy host first, each with the call that gave it`() {
        vendor.answer(
            serviceWallet,
            """{"id":7,"wallet_type":"service_wallet","currency":"KES","available_balance":49.5}""",
        )
        vendor.answer(
            paymentWallet,
            """{"wallet_type":"payment_wallet","currency":"KES","available_balance":"15101.36"}""",
        )

        val read = read()

        assertEquals(kes(4_950, PayHeroBalances.SERVICE_WALLET), read.fees)
        assertEquals(kes(1_510_136, PayHeroBalances.PAYMENT_WALLET), read.payout)
        assertEquals(listOf(serviceWallet, paymentWallet), vendor.asked, "nothing else is asked once both are read")
    }

    @Test
    fun `a wallet query that ignores the type is not believed to be the payment wallet`() {
        val service = """{"wallet_type":"service_wallet","currency":"KES","available_balance":49.5}"""
        vendor.answer(serviceWallet, service)
        vendor.answer(paymentWallet, service)

        val read = read(accountId = null)

        assertEquals(kes(4_950, PayHeroBalances.SERVICE_WALLET), read.fees)
        assertNull(read.payout, "unknown, never the service wallet's number and never zero")
        assertEquals(listOf(serviceWallet, paymentWallet), vendor.asked, "no account id: the account calls are skipped")
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
        assertEquals(kes(2_000, PayHeroBalances.SERVICE_WALLET), read().fees)
    }

    @Test
    fun `the fee balance falls back to the account's service wallet`() {
        vendor.answer(accountBalance, """{"balance":300,"service_wallet_balance":12}""")
        assertEquals(
            ProviderBalances(kes(30_000, PayHeroBalances.ACCOUNT_BALANCE), kes(1_200, PayHeroBalances.ACCOUNT_BALANCE)),
            read(),
            "the new balance answer carried both",
        )

        vendor.answer(accountBalance, """{"balance":300}""")
        vendor.answer(account, """{"account":{"id":63,"currency":"KES","service_wallet_balance":7.25}}""")
        assertEquals(kes(725, PayHeroBalances.ACCOUNT_SERVICE_WALLET), read().fees)
        assertEquals(account, vendor.asked.last())
    }

    @Test
    fun `a balance that can't be read is null, never zero`() {
        vendor.answer(serviceWallet, """{"wallet_type":"service_wallet"}""")
        vendor.answer(paymentWallet, """{"error_message":"unknown wallet type"}""", 400)
        vendor.answer(accountBalance, """{"balance":{"available":10}}""")
        vendor.answer(account, "<html>nope</html>")

        assertEquals(ProviderBalances(null, null), read(), "answered, but nothing this client can read")

        vendor.answer(accountBalance, """{"balance":"12"}""")
        vendor.fail(serviceWallet) { ConnectException("refused") }
        assertEquals(ProviderBalances(kes(1_200, PayHeroBalances.ACCOUNT_BALANCE), null), read())
    }

    @Test
    fun `with nothing read, refused credentials and an unreachable provider are errors`() {
        listOf(serviceWallet, paymentWallet, accountBalance, account).forEach { vendor.answer(it, "", 401) }
        assertIs<IntegrationError.Rejected>((balances() as Resource.Error).error)

        listOf(serviceWallet, paymentWallet, accountBalance, account).forEach { where ->
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
