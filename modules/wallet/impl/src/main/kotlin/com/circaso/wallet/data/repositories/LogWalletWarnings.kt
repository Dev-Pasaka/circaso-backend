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
}
