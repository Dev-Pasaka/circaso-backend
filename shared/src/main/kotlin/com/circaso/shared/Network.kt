package com.circaso.shared

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Which side of the chains money is on (decision 2026-11-18): one server talks to Circle's testnet and mainnet side
 * by side, and everything about money carries its network. The wire form is the name, upper case, everywhere.
 */
enum class Network {
    MAINNET,
    TESTNET,
    ;

    companion object {
        /** A chain's name ends in one of these exactly when it is a test network; names never overlap between the two. */
        private val TESTNET_SUFFIXES = listOf("SEPOLIA", "AMOY", "DEVNET", "FUJI", "TESTNET")

        /** The wire form, or null for anything else (a blank or unknown value, any other case). */
        fun parse(raw: String?): Network? = entries.firstOrNull { it.name == raw }

        /** The network a chain belongs to, read from Circle's name for it (`BASE-SEPOLIA` is testnet, `BASE` mainnet). */
        fun ofChain(blockchain: String): Network {
            val name = blockchain.uppercase()
            return if (TESTNET_SUFFIXES.any { name.endsWith(it) }) TESTNET else MAINNET
        }
    }
}

/**
 * The network the work in this coroutine is for. A request sets it once at the edge (the gateway, from
 * `X-Wallet-Network`), a job once per network it runs for, a webhook from the vendor path it arrived on, an event
 * handler from the event; the wallet's rows, Redis keys and custody calls read it from here, so one request can never
 * touch two networks. Without one, work is for [Network.TESTNET], which is all there was before networks existed.
 */
class WalletNetwork(val network: Network) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WalletNetwork>

    override fun toString(): String = "WalletNetwork($network)"
}

/** The network this coroutine works for; [Network.TESTNET] when nothing set one. */
suspend fun currentNetwork(): Network = coroutineContext[WalletNetwork]?.network ?: Network.TESTNET

/**
 * Decides the network of a phone's request (implemented by the wallet, which owns who may use which network): the one
 * [requested] in `X-Wallet-Network` when the person may use it, the fallback for an app that sends none, or
 * `wallet.network_unavailable`.
 */
fun interface WalletNetworkGate {
    suspend fun resolve(userId: UserId, requested: String?): Resource<Network>
}

/**
 * The network something was explicitly asked for, or null when none was: staff reads, which cover both networks
 * unless one is named (`?network=`), filter by this instead of [currentNetwork].
 */
suspend fun networkFilter(): Network? = coroutineContext[WalletNetwork]?.network
