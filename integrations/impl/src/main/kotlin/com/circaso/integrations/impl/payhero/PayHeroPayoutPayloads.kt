package com.circaso.integrations.impl.payhero

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The payout half's bodies. Only the withdraw request and its 200 are documented (docs.payhero.africa); the status,
// balance and error answers are read from every field name either site has shown, all optional, so an answer that
// names things differently is "not read" rather than a crash or a wrong number. Money is read as text and parsed
// exactly, whether PayHero sends a number or a string.

/** `POST /api/v2/withdraw` to a phone. PayHero takes whole shillings and the local form of the number. */
@Serializable
internal data class WithdrawBody(
    @SerialName("external_reference") val externalReference: String,
    @SerialName("account_id") val accountId: Long,
    val amount: Long,
    @SerialName("phone_number") val phoneNumber: String,
    @SerialName("account_number") val accountNumber: String,
    @SerialName("network_code") val networkCode: String,
    val channel: String,
    @SerialName("recipient_type") val recipientType: String,
    @SerialName("callback_url") val callbackUrl: String,
)

/** The documented 200 is `merchant_reference` + `checkout_request_id`; the collection reply's names are read too. */
@Serializable
internal data class WithdrawReply(
    @SerialName("merchant_reference") val merchantReference: String? = null,
    val reference: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("checkout_request_id") val checkoutRequestId: String? = null,
    @SerialName("CheckoutRequestID") val checkoutRequestIdLegacy: String? = null,
) {
    /** The key the status lookup is documented to take, else the next best id the answer carries. */
    val lookupKey: String?
        get() = listOf(merchantReference, reference, conversationId).firstOrNull { !it.isNullOrBlank() }

    val otherId: String?
        get() = listOf(checkoutRequestId, checkoutRequestIdLegacy).firstOrNull { !it.isNullOrBlank() }
}

/** `POST /api/global/transaction-status`: the reference the create call returned. */
@Serializable
internal data class StatusRequest(
    @SerialName("request_id") val requestId: String,
)

/** A refusal:`error_code`, `error_message`, and for a malformed request every bad field in `invalid_arguments`. */
@Serializable
internal data class PayoutRefusal(
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
    val message: String? = null,
    @SerialName("invalid_arguments") val invalidArguments: List<InvalidArgument>? = null,
) {
    val said: String get() = listOfNotNull(errorCode, errorMessage ?: message).joinToString(": ")
}

@Serializable
internal data class InvalidArgument(val field: String? = null)

/**
 * A status answer from either lookup. The new one's schema is undocumented, so the transaction is looked for at the
 * top level and under the wrappers PayHero uses elsewhere (`data`, `response`, `transaction`), in snake_case and in
 * the callback's PascalCase.
 */
@Serializable
internal data class PayoutLookupReply(
    val status: String? = null,
    @SerialName("Status") val statusLegacy: String? = null,
    @SerialName("transaction_status") val transactionStatus: String? = null,
    val amount: String? = null,
    @SerialName("Amount") val amountLegacy: String? = null,
    val currency: String? = null,
    @SerialName("provider_reference") val providerReference: String? = null,
    @SerialName("third_party_reference") val thirdPartyReference: String? = null,
    @SerialName("MpesaReceiptNumber") val mpesaReceipt: String? = null,
    @SerialName("external_reference") val externalReference: String? = null,
    @SerialName("ExternalReference") val externalReferenceLegacy: String? = null,
    val message: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("ResultCode") val resultCode: String? = null,
    @SerialName("ResultDesc") val resultDesc: String? = null,
    @SerialName("TransactionReceipt") val transactionReceipt: String? = null,
    val data: PayoutLookupReply? = null,
    val response: PayoutLookupReply? = null,
    val transaction: PayoutLookupReply? = null,
) {
    /**
     * Where the transaction's own status may be: inside a wrapper when there is one, and only otherwise at the top.
     * A wrapper's own `status` is never read as the payout's: it may only say that the query worked, and reading
     * "success" there as "paid" would complete an off-ramp nobody was paid for.
     */
    val candidates: List<PayoutLookupReply>
        get() = listOfNotNull(data, response, transaction).ifEmpty { listOf(this) }

    val statusWords: List<String> get() = listOfNotNull(transactionStatus, statusLegacy, status)

    val paidAmount: String? get() = amount ?: amountLegacy

    val receipt: String?
        get() =
            listOf(providerReference, thirdPartyReference, mpesaReceipt, transactionReceipt).firstOrNull {
                !it.isNullOrBlank()
            }

    val ours: String? get() = listOf(externalReference, externalReferenceLegacy).firstOrNull { !it.isNullOrBlank() }

    val said: String get() = listOfNotNull(errorMessage, message).joinToString(": ")
}

/**
 * The new account balance as FinoQ really answers it (first seen 2026-11-23, on a real account):
 * `{"service_wallet_balance":{"account_id":…,"balance":68,"currency":"KES"},"wallet_balance":{"account_id":…,"currency":"KES"}}`.
 * `wallet_balance` is the wallet a withdraw spends; FinoQ leaves `balance` out of it when it is empty (the same
 * account's withdraw was refused for "insufficient balance" in the same minute), so a present wallet with no
 * `balance` reads as zero. A missing `wallet_balance` object stays unknown.
 */
@Serializable
internal data class AccountBalancesReply(
    @SerialName("service_wallet_balance") val serviceWallet: WalletPart? = null,
    @SerialName("wallet_balance") val wallet: WalletPart? = null,
) {
    @Serializable
    internal data class WalletPart(
        @SerialName("account_id") val accountId: Long? = null,
        val balance: String? = null,
        val currency: String? = null,
    ) {
        /** What it holds; an object FinoQ sent without `balance` is an empty wallet. */
        val heldOrZero: String get() = balance?.takeIf { it.isNotBlank() } ?: "0"
    }
}

/**
 * A balance answer: the legacy wallet (`available_balance`), the account on the auth host
 * (`account.service_wallet_balance`), and the new account balance, whose schema is undocumented.
 */
@Serializable
internal data class BalanceReply(
    @SerialName("available_balance") val availableBalance: String? = null,
    @SerialName("payment_wallet_balance") val paymentWalletBalance: String? = null,
    @SerialName("account_balance") val accountBalance: String? = null,
    val balance: String? = null,
    val available: String? = null,
    @SerialName("service_wallet_balance") val serviceWalletBalance: String? = null,
    val currency: String? = null,
    @SerialName("wallet_type") val walletType: String? = null,
    val data: BalanceReply? = null,
    val account: BalanceReply? = null,
    val wallet: BalanceReply? = null,
) {
    val candidates: List<BalanceReply> get() = listOfNotNull(this, data, account, wallet)

    /** What a wallet or account holds, by the names seen for it; never the service wallet's field. */
    val held: String?
        get() =
            listOf(availableBalance, paymentWalletBalance, accountBalance, balance, available).firstOrNull {
                !it.isNullOrBlank()
            }
}
