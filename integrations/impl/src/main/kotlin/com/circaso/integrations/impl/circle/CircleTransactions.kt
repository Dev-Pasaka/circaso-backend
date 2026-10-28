package com.circaso.integrations.impl.circle

import com.circaso.integrations.api.CustodyDirection
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
 * Circle's transaction (the API's and the webhook's, which share a shape) in our words. Only USDC on a supported
 * chain is followed, so a transaction in any other token translates to null and is left alone.
 */
internal class CircleTransactions(private val tokens: CircleTokens, private val settings: CircleSettings) {
    private val log = LoggerFactory.getLogger(CircleTransactions::class.java)

    suspend fun translate(transaction: TransactionPayload): Resource<CustodyTransaction?> {
        val direction = directionOf(transaction.transactionType)
        if (transaction.id.isEmpty() || direction == null || transaction.tokenId.isEmpty()) {
            return unreadable("id, type or token")
        }

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
            CustodyTransaction(
                circleTxId = transaction.id,
                circleUserId = transaction.userId,
                walletId = transaction.walletId,
                blockchain = transaction.blockchain,
                direction = direction,
                state = stateOf(transaction.state),
                amount = Money(amountMinor, Asset.USDC),
                sourceAddress = transaction.sourceAddress,
                destinationAddress = transaction.destinationAddress,
                refId = transaction.refId,
                txHash = transaction.txHash,
                failureReason = transaction.errorReason,
                updatedAt = instantOf(transaction.updateDate) ?: instantOf(transaction.createDate) ?: Instant.EPOCH,
            ),
        )
    }

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
