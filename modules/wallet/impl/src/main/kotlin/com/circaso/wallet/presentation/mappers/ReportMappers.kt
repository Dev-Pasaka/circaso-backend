package com.circaso.wallet.presentation.mappers

import com.circaso.wallet.api.ChainAssetTotals
import com.circaso.wallet.api.FinanceReport
import com.circaso.wallet.api.GroupWalletPage
import com.circaso.wallet.api.GroupWalletRef
import com.circaso.wallet.api.OpsBalance
import com.circaso.wallet.api.OpsChainWallet
import com.circaso.wallet.api.OpsHoldings
import com.circaso.wallet.api.OpsTransfer
import com.circaso.wallet.api.PeriodTotals
import com.circaso.wallet.api.TransferPage
import com.circaso.wallet.domain.models.FinanceTotals
import com.circaso.wallet.domain.models.GroupWalletResults
import com.circaso.wallet.domain.models.StaffHoldings
import com.circaso.wallet.domain.models.StaffTransfer
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.models.TransferResults
import com.circaso.wallet.domain.models.WalletOwner
import com.circaso.wallet.domain.support.StaffReports

// The wallet's staff contract (decision 2026-11-08) from its domain models: dollar figures in micro-dollars, coin
// amounts exact in minor units, states and origins by name.

fun StaffHoldings.toOps() =
    OpsHoldings(
        hasWallet = hasWallet,
        wallets =
            wallets.map { chain ->
                OpsChainWallet(
                    blockchain = chain.wallet.blockchain,
                    address = chain.wallet.address,
                    state = chain.wallet.state.name,
                    balances =
                        chain.balances.map {
                            OpsBalance(it.holding.symbol, it.holding.amountMinor, it.holding.decimals, it.usdMicros)
                        },
                )
            },
        totalUsdMicros = totalUsdMicros,
        balancesAvailable = balancesAvailable,
    )

fun Transfer.toOps(toFeeWallet: Boolean = false) =
    OpsTransfer(
        transferId = transferId,
        senderId = senderId,
        recipientId = recipientId,
        destinationAddress = destinationAddress,
        blockchain = blockchain,
        asset = amount.asset.symbol,
        amountMinor = amount.amountMinor,
        decimals = amount.asset.decimals,
        feeMinor = feeMinor,
        usdTotalMicros = usdTotalMinor,
        state = state.name,
        origin = origin.name,
        kind = StaffReports.kindOf(this),
        txHash = txHash,
        failureReason = failureReason,
        createdAt = createdAt,
        updatedAt = updatedAt,
        fromGroupId = fromGroupId,
        toGroupId = toGroupId,
        feeAddress = feeAddress,
        toFeeWallet = toFeeWallet,
    )

fun StaffTransfer.toOps() = transfer.toOps(toFeeWallet)

fun TransferResults.toOps() = TransferPage(transfers.map { it.toOps(it.transferId in toFeeWallet) }, nextCursor)

fun FinanceTotals.toOps() =
    FinanceReport(
        series = series.map { PeriodTotals(it.period, it.count, it.volumeUsdMicros, it.feesUsdMicros) },
        byChainAsset =
            byChainAsset.map {
                ChainAssetTotals(
                    blockchain = it.sums.blockchain,
                    asset = it.sums.asset,
                    count = it.sums.count,
                    amountMinor = it.sums.amountMinor,
                    decimals = it.sums.decimals,
                    volumeUsdMicros = it.sums.volumeUsdMicros,
                    feesUsdMicros = it.feesUsdMicros,
                )
            },
        count = count,
        volumeUsdMicros = volumeUsdMicros,
        feesUsdMicros = feesUsdMicros,
    )

fun WalletOwner.Group.toRef() = GroupWalletRef(chatId, walletId.value, signerId, createdAt)

fun GroupWalletResults.toOps() = GroupWalletPage(owners.map { it.toRef() }, nextCursor)
