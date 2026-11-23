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
 * A merchant's three balances at PayHero/FinoQ (2026-11-24, the owner's "the PayHero service wallet is for on-ramp,
 * the FinoQ service wallet is for off-ramp"), each from its own wallet and **never filled in from another's**, with
 * the call's name beside the number so staff can see which one answered:
 *
 * - **Collection fees** (PayHero's service wallet, what an M-Pesa prompt's fee is taken from): the legacy wallet
 *   query ([SERVICE_WALLET]) on the host the prompts go to. Nothing else.
 * - **Payout fees** (FinoQ's service wallet): the account balance's `service_wallet_balance` ([ACCOUNT_SERVICE]),
 *   then the Team on the auth host ([ACCOUNT_SERVICE_WALLET]), both FinoQ's own account.
 * - **Payout** (FinoQ's payment wallet, what a withdraw spends): the account balance's `wallet_balance`
 *   ([ACCOUNT_WALLET]), or a balance in a layout this client knows from an older answer ([ACCOUNT_BALANCE]).
 *
 * Until 2026-11-24 the fee balance came from the legacy wallet first and fell back to FinoQ's service wallet, which
 * mixed two wallets under one name; and a guessed legacy `payment_wallet` query (never documented, always `404` on the
 * dev server) is no longer asked. A call that fails, or answers without a number this class can read, leaves its
 * balance **null**; it is never zero by default, and the FinoQ wallets need the account id.
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
        val collectionFees = serviceWallet(calls, answers)
        var payoutFees: BalanceReading? = null
        var payout: BalanceReading? = null

        if (account != null) {
            val body =
                askBody(ACCOUNT_BALANCE, answers) { calls.account(api, "$ACCOUNT_BALANCE_PATH/$account/balance", it) }
            val split = body?.let(::accountBalances)
            if (split != null) {
                payout = split.wallet?.let { reading(it.heldOrZero, it.currency, ACCOUNT_WALLET) }
                payoutFees =
                    split.serviceWallet?.balance?.let { reading(it, split.serviceWallet.currency, ACCOUNT_SERVICE) }
            } else {
                val reply = body?.let { decode(ACCOUNT_BALANCE, it) }
                payout = reply?.candidates?.firstNotNullOfOrNull { reading(it.held, it.currency, ACCOUNT_BALANCE) }
                payoutFees = reply?.let { serviceWalletIn(it, ACCOUNT_SERVICE) }
            }
        }
        if (account != null && payoutFees == null) {
            val reply = ask(ACCOUNT_SERVICE_WALLET, answers) { calls.account(auth, "$ACCOUNT_PATH/$account", it) }
            payoutFees = reply?.let { serviceWalletIn(it, ACCOUNT_SERVICE_WALLET) }
        }

        val balances = ProviderBalances(collectionFees, payoutFees, payout)
        log.info(
            "payhero balances: payhero_service={} finoq_service={} finoq_payment={}",
            collectionFees?.source ?: "unread",
            payoutFees?.source ?: "unread",
            payout?.source ?: "unread",
        )
        if (!balances.isEmpty) return Resource.success(balances)
        return nothingRead(answers)
    }

    private suspend fun serviceWallet(calls: Calls, answers: MutableList<Answer>): BalanceReading? =
        ask(SERVICE_WALLET, answers) { calls.wallet(SERVICE_WALLET_TYPE, it) }
            ?.takeIf { it.walletType == null || it.walletType.equals(SERVICE_WALLET_TYPE, ignoreCase = true) }
            ?.let { reading(it.held, it.currency, SERVICE_WALLET) }

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
                Resource.success(ProviderBalances())
            }
        }
    }

    /** One balance call, by [name]: its answer is recorded in [answers], and its body is read when it is a 2xx. */
    private suspend fun ask(
        name: String,
        answers: MutableList<Answer>,
        send: suspend (String) -> HttpResponse,
    ): BalanceReply? = askBody(name, answers, send)?.let { decode(name, it) }

    /** One balance call's body when it is a 2xx, its answer recorded in [answers]. */
    private suspend fun askBody(
        name: String,
        answers: MutableList<Answer>,
        send: suspend (String) -> HttpResponse,
    ): String? {
        val answer = log.ask(name) { send(name) }
        answers += answer
        if (answer !is Answer.Replied) return null
        shapes.note(name, answer.status, answer.body)
        if (answer.status !in SUCCESS) {
            log.warn("payhero balance not given: source={} status={}", name, answer.status)
            return null
        }
        return answer.body
    }

    private fun decode(name: String, body: String): BalanceReply? {
        val reply = runCatching { payHeroJson.decodeFromString(BalanceReply.serializer(), body) }.getOrNull()
        if (reply == null) log.warn("payhero balance not read: source={} (a layout this client doesn't know)", name)
        return reply
    }

    /** The account balance in FinoQ's real two-wallet layout, or null when the answer isn't that layout. */
    private fun accountBalances(body: String): AccountBalancesReply? =
        runCatching { payHeroJson.decodeFromString(AccountBalancesReply.serializer(), body) }
            .getOrNull()
            ?.takeIf { it.wallet != null || it.serviceWallet != null }

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
        const val ACCOUNT_BALANCE = "finoq.account.balance"

        /** FinoQ's service wallet in the new balance answer: what a payout's fee is taken from. */
        const val ACCOUNT_SERVICE = "finoq.balance.service_wallet"

        /** The account's own wallet in the new balance answer: what a withdraw spends (confirmed 2026-11-23). */
        const val ACCOUNT_WALLET = "finoq.account.wallet_balance"

        /** FinoQ's service wallet as the Team on the auth host reports it (`account.service_wallet_balance`). */
        const val ACCOUNT_SERVICE_WALLET = "finoq.account.service_wallet_balance"

        const val WALLETS_PATH = "/api/v2/wallets"
        const val ACCOUNT_BALANCE_PATH = "/api/global/accounts"
        const val ACCOUNT_PATH = "/api/v2/account"

        private const val SERVICE_WALLET_TYPE = "service_wallet"
        private val SUCCESS = 200..299
        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
    }
}
