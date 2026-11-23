package com.circaso.integrations.impl.circle

import com.circaso.integrations.api.CustodyDirection
import com.circaso.integrations.api.CustodyTransaction
import com.circaso.integrations.api.DepositQuery
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.MerchantFloat
import com.circaso.integrations.api.StablecoinCustody
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.Network
import com.circaso.shared.Resource
import io.ktor.http.encodeURLParameter
import java.math.BigInteger

/**
 * A ramp merchant's float as Circle holds it (decision 2026-11-22): `GET /v1/w3s/wallets/{id}/balances` with the
 * account's API key alone, so the app's processes can read it without the entity secret. Only the USDC contract the
 * network's settings name for the token's chain counts: a token that merely calls itself USDC is ignored.
 *
 * A deposit (the off-ramp, 2026-11-23) is read from Circle's own records of the chain transaction
 * (`GET /v1/w3s/transactions?txHash=&blockchain=`, the same call the wallet uses to settle a split payment, with the
 * developer's key, which lists every wallet's record of it, the developer's own included), narrowed to the incoming
 * transfers of the merchant's wallet. The ids, the coin and the state are Circle's, translated as every other
 * transaction is.
 */
internal class CircleMerchantFloat(
    private val accounts: Map<Network, CircleAccount>,
    private val custodies: Map<Network, StablecoinCustody> = emptyMap(),
) : MerchantFloat {
    constructor(circle: CircleAccounts) : this(circle.accounts, circle.custodies)

    override suspend fun deposits(network: Network, query: DepositQuery): Resource<List<CustodyTransaction>> {
        val custody = custodies[network] ?: return unavailable()
        if (query.txHash.isBlank()) return Resource.success(emptyList())
        return when (val read = custody.chainTransaction(query.blockchain, query.txHash)) {
            is Resource.Error -> {
                read
            }

            is Resource.Success -> {
                Resource.success(
                    read.data.filter { it.walletId == query.walletId && it.direction == CustodyDirection.IN },
                )
            }
        }
    }

    override suspend fun balance(network: Network, walletId: String): Resource<Money> {
        val account = accounts[network] ?: return unavailable()
        val path = "/v1/w3s/wallets/${walletId.encodeURLParameter()}/balances?pageSize=$PAGE_SIZE"
        return when (val reply = account.client.get<BalancesData>(path, "get_merchant_float")) {
            is CircleReply.Ok -> Resource.success(Money(usdcOf(reply.data, account), Asset.USDC))
            is CircleReply.Unreachable -> Resource.error(IntegrationError.Unavailable(CircleErrors.VENDOR), reply.cause)
            is CircleReply.Failed -> unavailable()
        }
    }

    private fun usdcOf(held: BalancesData, account: CircleAccount): BigInteger {
        val chains = account.settings().supported
        return held.tokenBalances
            .filter { balance ->
                val contract =
                    chains
                        .find(balance.token.blockchain)
                        ?.assets
                        ?.firstOrNull { it.symbol == Asset.USDC.symbol }
                        ?.tokenAddress
                !contract.isNullOrBlank() && sameContract(contract, balance.token.tokenAddress)
            }.mapNotNull { CircleTransactions.minorUnits(it.amount, Asset.USDC.decimals) }
            .fold(BigInteger.ZERO, BigInteger::add)
    }

    private fun <T> unavailable(): Resource<T> = Resource.error(IntegrationError.Unavailable(CircleErrors.VENDOR))

    private companion object {
        const val PAGE_SIZE = 50

        /** EVM addresses compare without case; anything else (a Solana mint) exactly. */
        fun sameContract(a: String, b: String): Boolean =
            if (a.startsWith("0x")) a.equals(b.trim(), ignoreCase = true) else a == b.trim()
    }
}
