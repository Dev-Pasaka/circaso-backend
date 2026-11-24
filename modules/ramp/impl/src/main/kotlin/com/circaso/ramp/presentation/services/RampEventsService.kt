package com.circaso.ramp.presentation.services

import com.circaso.integrations.api.KycEnvironment
import com.circaso.ramp.domain.ports.LoggedFrame
import com.circaso.ramp.domain.support.MerchantTopUps
import com.circaso.ramp.domain.support.TestPayouts
import com.circaso.ramp.domain.usecases.AnnounceVerifications
import com.circaso.ramp.domain.usecases.CallbackSigningSecret
import com.circaso.ramp.domain.usecases.EndSessions
import com.circaso.ramp.domain.usecases.HandleKycNotification
import com.circaso.ramp.domain.usecases.HandlePaymentCallback
import com.circaso.ramp.domain.usecases.HandlePayoutCallback
import com.circaso.ramp.domain.usecases.HandleSaleTransfer
import com.circaso.ramp.domain.usecases.HasOpenOrder
import com.circaso.ramp.domain.usecases.ReleaseOrders
import com.circaso.ramp.domain.usecases.ReplayFrames
import com.circaso.ramp.domain.usecases.RunRechecks
import com.circaso.ramp.domain.usecases.SettleFees
import com.circaso.ramp.domain.usecases.SweepOrders
import com.circaso.ramp.domain.usecases.SweepSales
import com.circaso.ramp.domain.usecases.SweepVerifications
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.util.UUID

/**
 * What other parts of the system tell or ask the ramp: the payment provider's callback, the identity provider's
 * webhook, the app's jobs, the gateway's replay and its `api`.
 */
class RampEventsService(
    private val handlePaymentCallback: HandlePaymentCallback,
    private val sweepOrders: SweepOrders,
    private val endSessionsUseCase: EndSessions,
    private val replayFrames: ReplayFrames,
    private val hasOpenOrder: HasOpenOrder,
    private val handleKycNotification: HandleKycNotification,
    private val sweepVerifications: SweepVerifications,
    private val handlePayoutCallback: HandlePayoutCallback,
    private val handleSaleTransfer: HandleSaleTransfer,
    private val sweepSales: SweepSales,
    private val runRechecks: RunRechecks,
    private val testPayouts: TestPayouts,
    private val callbackSigningSecret: CallbackSigningSecret,
    private val topUps: MerchantTopUps,
    private val announceVerificationsUseCase: AnnounceVerifications,
) {
    /** The webhook signing secret for a callback on the address ending in [callbackKey], or null. */
    suspend fun callbackSecret(callbackKey: String): String? = callbackSigningSecret(callbackKey)

    suspend fun payoutCallback(callbackKey: String, reference: String): Resource<Unit> =
        handlePayoutCallback(callbackKey, reference)

    /** The wallet confirmed a transfer that names a ramp order (W4 `rampOrderId`). */
    suspend fun saleTransfer(rampOrderId: UUID, transferId: UUID): Resource<Unit> =
        handleSaleTransfer(rampOrderId, transferId)

    /** `ramp.payouts` on one network: funding, payouts, lookups, expiry of unfunded sales. */
    suspend fun sweepSales(network: Network): Int = sweepSales.invoke(network)

    /** `ramp.payouts`, once per run: staff's re-checks, the merchants' test payouts and top-ups in flight. */
    suspend fun rechecksAndTests(): Int = runRechecks() + testPayouts.sweep() + topUps.sweep()

    suspend fun kycNotification(environment: KycEnvironment, sessionId: String): Resource<Unit> =
        handleKycNotification(environment, sessionId)

    suspend fun sweepVerifications(): Int = sweepVerifications.invoke()

    /** `ramp.announce-verifications`: users' recent live verifications said again (the verified badge). */
    suspend fun announceVerifications(): Int = announceVerificationsUseCase()

    suspend fun paymentCallback(callbackKey: String, reference: String): Resource<Unit> =
        handlePaymentCallback(callbackKey, reference)

    suspend fun sweep(network: Network): Int = sweepOrders(network)

    suspend fun endSessions(): Int = endSessionsUseCase()

    suspend fun missedFrames(userId: UserId, seq: Long): Resource<List<LoggedFrame>?> = replayFrames(userId, seq)

    suspend fun hasOpenOrder(userId: UserId): Boolean = hasOpenOrder.invoke(userId)
}

/** The ops process's jobs (decision 2026-11-22): the release and the daily sweep, which only it can sign. */
class RampOpsJobsService(private val releaseOrders: ReleaseOrders, private val settleFees: SettleFees) {
    suspend fun release(network: Network): Int = releaseOrders(network)

    suspend fun settle(network: Network): Int = settleFees(network)
}
