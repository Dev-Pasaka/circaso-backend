package com.circaso.wallet.data.repositories

import com.circaso.wallet.domain.ports.WalletWarnings
import org.slf4j.LoggerFactory

/** The wallet's warnings as log lines (`LOG_LEVEL_WALLET`): the chain and Circle's wallet id, never the user. */
class LogWalletWarnings : WalletWarnings {
    private val log = LoggerFactory.getLogger(LogWalletWarnings::class.java)

    override fun chainNotConfigured(blockchain: String, circleWalletId: String) {
        log.warn(
            "wallet on a chain no longer configured, its balance can't be read: blockchain={} circleWalletId={}",
            blockchain,
            circleWalletId,
        )
    }

    override fun refIdOfAnotherWallet(transferId: String, circleWalletId: String) {
        log.warn(
            "circle update names a transfer of another wallet, not applied: transferId={} circleWalletId={}",
            transferId,
            circleWalletId,
        )
    }

    override fun unknownWallet(blockchain: String, circleWalletId: String) {
        log.warn(
            "circle notified a transaction of a wallet we don't know, ignored: blockchain={} circleWalletId={}",
            blockchain,
            circleWalletId,
        )
    }
}
