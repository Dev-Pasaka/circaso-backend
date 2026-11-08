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

    override fun splitPaymentAmbiguous(circleTxId: String, candidates: Int) {
        log.warn(
            "split payment matched several transfers by its legs, took the newest: circleTxId={} candidates={}",
            circleTxId,
            candidates,
        )
    }

    override fun transferStuck(transferId: String, blockchain: String) {
        log.warn(
            "transfer still submitted an hour after it was prepared: transferId={} blockchain={}",
            transferId,
            blockchain,
        )
    }

    override fun jobBackingOff(job: String, skipRuns: Int) {
        log.warn("circle unreachable from a wallet job, backing off: job={} skipRuns={}", job, skipRuns)
    }

    override fun unknownWallet(blockchain: String, circleWalletId: String) {
        log.warn(
            "circle notified a transaction of a wallet we don't know, ignored: blockchain={} circleWalletId={}",
            blockchain,
            circleWalletId,
        )
    }

    override fun gasAboveShare(
        transferId: String,
        blockchain: String,
        shareBps: Int,
    ) {
        log.warn(
            "network cost above the whole fee: transferId={} blockchain={} shareBps={}",
            transferId,
            blockchain,
            shareBps,
        )
    }

    override fun feeNotTaken(blockchain: String, reason: String) {
        log.warn("a fee rule applies but no fee can be taken: blockchain={} reason={}", blockchain, reason)
    }
}
