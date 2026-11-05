package com.circaso.wallet.domain.usecases

import com.circaso.wallet.domain.ports.SETUP_SWEEP_WINDOW
import com.circaso.wallet.domain.support.SetupProgress
import java.time.Clock

/**
 * `wallet.setup-sweep`, every [SETUP_SWEEP_INTERVAL]: the wallets whose setup began within [SETUP_SWEEP_WINDOW] are
 * read from Circle, so one whose webhook was lost turns `ACTIVE` (and a space goes live) within half a minute even when
 * no app is polling. Returns how many were read.
 */
class SweepWalletSetups(private val progress: SetupProgress, private val clock: Clock) {
    suspend operator fun invoke(): Int = progress.sweep(clock.instant().minus(SETUP_SWEEP_WINDOW), BATCH)

    companion object {
        val SETUP_SWEEP_INTERVAL: java.time.Duration = java.time.Duration.ofSeconds(30)
        const val BATCH = 50
    }
}
