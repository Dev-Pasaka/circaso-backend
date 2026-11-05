package com.circaso.integrations.impl.circle

import com.circaso.integrations.api.CustodyDirection
import com.circaso.integrations.api.CustodyOperation
import com.circaso.integrations.api.CustodyTransaction
import com.circaso.integrations.api.CustodyTransactionState
import com.circaso.integrations.api.IntegrationError
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.Resource
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * Circle's transaction (the API's and the webhook's, which share most of a shape) in our words. Only USDC on a
 * supported chain is followed, so a transaction in any other token translates to null and is left alone.
 */
internal class CircleTransactions(private val tokens: CircleTokens, private val settings: CircleSettings) {
    private val log = LoggerFactory.getLogger(CircleTransactions::class.java)

    suspend fun translate(transaction: TransactionPayload): Resource<CustodyTransaction?> {
        val direction = directionOf(transaction.transactionType)
        // No token: a contract execution. The webhook's body may not say `operation` (the API's does), so the
        // missing token is what tells; it was read as unreadable, and Circle retried it for nothing (2026-11-05).
        if (transaction.tokenId.isEmpty()) return Resource.success(execution(transaction, direction))
        if (transaction.id.isEmpty() || direction == null) return unreadable("id or type")

        val token =
            when (val resolved = tokens.resolve(transaction.tokenId)) {
                is Resource.Success -> resolved.data
                is Resource.Error -> return resolved
            }
        // TODO(decision: how a transaction in an offered asset other than USDC is reported; Money holds only Asset)
        if (!settings.isUsdc(token.tokenAddress, token.blockchain)) return Resource.success(null)

        val amountMinor =
            transaction.amounts.firstOrNull()?.let { minorUnits(it, Asset.USDC) } ?: return unreadable("amount")
        return Resource.success(
            transaction.toCustody(direction, Money(amountMinor, Asset.USDC), CustodyOperation.TRANSFER),
        )
    }

    /**
     * A contract execution has no token or amount of its own. Ours (a split payment: the payee and Circaso's fee in
     * one batch, phase 2F) is outbound from the sender's wallet; it is reported with a zero amount, and the wallet
     * finds its transfer (Circle doesn't keep the `refId` we send with a user's contract execution, so usually by
     * its legs, which reach the receiving wallets as ordinary inbound transfers in the same chain transaction). An
     * inbound one, or one without an id, isn't a payment we follow.
     */
    private fun execution(transaction: TransactionPayload, direction: CustodyDirection?): CustodyTransaction? {
        // Every inbound record is a token transfer, so a token-less one that doesn't say its type is outbound.
        val way = direction ?: CustodyDirection.OUT.takeIf { transaction.transactionType.isEmpty() }
        if (transaction.id.isEmpty() || way != CustodyDirection.OUT) return null
        return transaction.toCustody(way, Money(0, Asset.USDC), CustodyOperation.CONTRACT_EXECUTION)
    }

    private fun TransactionPayload.toCustody(
        direction: CustodyDirection,
        amount: Money,
        operation: CustodyOperation,
    ) = CustodyTransaction(
        circleTxId = id,
        circleUserId = userId,
        walletId = walletId,
        blockchain = blockchain,
        direction = direction,
        state = stateOf(state),
        amount = amount,
        sourceAddress = sourceAddress,
        destinationAddress = destinationAddress,
        refId = refId,
        txHash = txHash,
        failureReason = errorReason,
        updatedAt = instantOf(updateDate) ?: instantOf(createDate) ?: Instant.EPOCH,
        networkFee = networkFee,
        operation = operation,
        developerWallet = custodyType == DEVELOPER,
    )

    private fun stateOf(state: String): CustodyTransactionState =
        when (state) {
            "STUCK" -> {
                CustodyTransactionState.STUCK
            }

            "CONFIRMED", "COMPLETE" -> {
                CustodyTransactionState.CONFIRMED
            }

            "FAILED", "DENIED", "CANCELLED" -> {
                CustodyTransactionState.FAILED
            }

            "INITIATED", "CLEARED", "QUEUED", "SENT" -> {
                CustodyTransactionState.SUBMITTED
            }

            else -> {
                // A state Circle added after this was written: still in flight, as far as we can tell.
                log.warn("circle sent an unknown transaction state: state={}", state)
                CustodyTransactionState.SUBMITTED
            }
        }

    private fun directionOf(type: String): CustodyDirection? =
        when (type) {
            "INBOUND" -> CustodyDirection.IN
            "OUTBOUND" -> CustodyDirection.OUT
            else -> null
        }

    private fun <T> unreadable(what: String): Resource<T> {
        log.warn("circle sent a transaction we can't read: {}", what)
        return Resource.error(
            IntegrationError.Rejected("The custody provider sent a transaction without a readable $what."),
        )
    }

    companion object {
        private const val DEVELOPER = "DEVELOPER"

        /** "20.5" USDC as 20 500 000 minor units; null if it isn't a number or has more decimals than [asset]. */
        fun minorUnits(decimal: String, asset: Asset): Long? =
            runCatching {
                BigDecimal(decimal)
                    .movePointRight(asset.decimals)
                    .setScale(0, RoundingMode.UNNECESSARY)
                    .longValueExact()
            }.getOrNull()

        /** [money] as the decimal string Circle expects ("20.5"). */
        fun decimalOf(money: Money): String =
            BigDecimal
                .valueOf(money.amountMinor, money.asset.decimals)
                .stripTrailingZeros()
                .toPlainString()

        private fun instantOf(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()
    }
}
