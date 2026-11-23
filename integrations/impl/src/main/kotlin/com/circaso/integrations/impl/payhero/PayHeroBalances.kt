package com.circaso.integrations.impl.payhero

import com.circaso.infrastructure.api.operation
import com.circaso.integrations.api.BalanceReading
import com.circaso.integrations.api.CollectionCredentials
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.ProviderBalances
import com.circaso.shared.Resource
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import org.slf4j.LoggerFactory

/**
 * A merchant's two balances at PayHero/FinoQ, each from the first call that gives it, with the call's name beside the
 * number so staff can see which one answered:
 *
 * - **Fees** (the service wallet PayHero takes its charges from): the legacy wallet query
 *   ([SERVICE_WALLET]), then the account on the auth host ([ACCOUNT_SERVICE_WALLET]).
 * - **Payout** (what a withdraw can spend): the legacy wallet query asked for a `payment_wallet`
 *   ([PAYMENT_WALLET]: the wallet the owner and the old dashboard name; the query is a guess, undocumented, and is
 *   believed only when the answer says it is that wallet), then the new account balance ([ACCOUNT_BALANCE]:
 *   documented, its answer isn't).
 *
 * Which of them a payout really spends is unconfirmed, which is why the source travels with the amount. A call that
 * fails, or answers without a number this class can read, leaves that balance **null**; it is never zero by default.
 */
class PayHeroBalances(hosts: PayHeroHosts) {
    private val log = LoggerFactory.getLogger(PayHeroBalances::class.java)
    private val shapes = ShapeLog(log)
    private val legacy = hosts.legacy
    private val api = hosts.api
    private val auth = hosts.auth

    suspend fun read(credentials: CollectionCredentials, accountId: String?): Resource<ProviderBalances> {
        val account = accountId?.trim()?.toLongOrNull()
        val answers = mutableListOf<Answer>()
        val calls = Calls(credentials)
        var fees = serviceWallet(calls, answers)
        var payout = paymentWallet(calls, answers)

        if (account != null && payout == null) {
            val reply =
                ask(ACCOUNT_BALANCE, answers) { calls.account(api, "$ACCOUNT_BALANCE_PATH/$account/balance", it) }
            payout = reply?.candidates?.firstNotNullOfOrNull { reading(it.held, it.currency, ACCOUNT_BALANCE) }
            fees = fees ?: reply?.let { serviceWalletIn(it, ACCOUNT_BALANCE) }
        }
        if (account != null && fees == null) {
            val reply = ask(ACCOUNT_SERVICE_WALLET, answers) { calls.account(auth, "$ACCOUNT_PATH/$account", it) }
            fees = reply?.let { serviceWalletIn(it, ACCOUNT_SERVICE_WALLET) }
        }

        log.info("payhero balances: payout={} fees={}", payout?.source ?: "unread", fees?.source ?: "unread")
        if (payout != null || fees != null) return Resource.success(ProviderBalances(payout, fees))
        return nothingRead(answers)
    }

    private suspend fun serviceWallet(calls: Calls, answers: MutableList<Answer>): BalanceReading? =
        ask(SERVICE_WALLET, answers) { calls.wallet(SERVICE_WALLET_TYPE, it) }
            ?.takeIf { it.walletType == null || it.walletType.equals(SERVICE_WALLET_TYPE, ignoreCase = true) }
            ?.let { reading(it.held, it.currency, SERVICE_WALLET) }

    /** Believed only when the answer names itself the payment wallet: a query that ignores the type isn't one. */
    private suspend fun paymentWallet(calls: Calls, answers: MutableList<Answer>): BalanceReading? =
        ask(PAYMENT_WALLET, answers) { calls.wallet(PAYMENT_WALLET_TYPE, it) }
            ?.takeIf { it.walletType.equals(PAYMENT_WALLET_TYPE, ignoreCase = true) }
            ?.let { reading(it.held, it.currency, PAYMENT_WALLET) }

    /** No balance at all: an error when no call could even be made, otherwise two honest nulls. */
    private fun nothingRead(answers: List<Answer>): Resource<ProviderBalances> {
        val replies = answers.filterIsInstance<Answer.Replied>()
        return when {
            replies.isEmpty() -> {
                Resource.error(IntegrationError.Unavailable(PayHeroCollections.VENDOR))
            }

            replies.all { it.status == UNAUTHORIZED || it.status == FORBIDDEN } -> {
                Resource.error(IntegrationError.Rejected("The payment provider refused the account's credentials."))
            }

            else -> {
                Resource.success(ProviderBalances(null, null))
            }
        }
    }

    /** One balance call, by [name]: its answer is recorded in [answers], and its body is read when it is a 2xx. */
    private suspend fun ask(
        name: String,
        answers: MutableList<Answer>,
        send: suspend (String) -> HttpResponse,
    ): BalanceReply? {
        val answer = log.ask(name) { send(name) }
        answers += answer
        if (answer !is Answer.Replied) return null
        shapes.note(name, answer.status, answer.body)
        if (answer.status !in SUCCESS) {
            log.warn("payhero balance not given: source={} status={}", name, answer.status)
            return null
        }
        val reply = runCatching { payHeroJson.decodeFromString(BalanceReply.serializer(), answer.body) }.getOrNull()
        if (reply == null) log.warn("payhero balance not read: source={} (a layout this client doesn't know)", name)
        return reply
    }

    private fun serviceWalletIn(reply: BalanceReply, source: String): BalanceReading? =
        reply.candidates.firstNotNullOfOrNull { reading(it.serviceWalletBalance, it.currency, source) }

    private fun reading(
        text: String?,
        currency: String?,
        source: String,
    ): BalanceReading? =
        PayHeroPayouts.amountOf(text?.takeIf { it.isNotBlank() }, currency)?.let { BalanceReading(it, source) }

    /** The requests, bound to one merchant's credentials. */
    private inner class Calls(private val credentials: CollectionCredentials) {
        suspend fun wallet(type: String, name: String): HttpResponse =
            legacy.get(WALLETS_PATH) {
                operation(name)
                authorize(credentials)
                parameter("wallet_type", type)
            }

        suspend fun account(
            client: HttpClient,
            path: String,
            name: String,
        ): HttpResponse =
            client.get(path) {
                operation(name)
                authorize(credentials)
            }
    }

    companion object {
        /** The names a reading carries ([BalanceReading.source]). */
        const val SERVICE_WALLET = "payhero.wallets.service_wallet"
        const val PAYMENT_WALLET = "payhero.wallets.payment_wallet"
        const val ACCOUNT_BALANCE = "finoq.account.balance"
        const val ACCOUNT_SERVICE_WALLET = "finoq.account.service_wallet_balance"

        const val WALLETS_PATH = "/api/v2/wallets"
        const val ACCOUNT_BALANCE_PATH = "/api/global/accounts"
        const val ACCOUNT_PATH = "/api/v2/account"

        private const val SERVICE_WALLET_TYPE = "service_wallet"
        private const val PAYMENT_WALLET_TYPE = "payment_wallet"
        private val SUCCESS = 200..299
        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
    }
}
