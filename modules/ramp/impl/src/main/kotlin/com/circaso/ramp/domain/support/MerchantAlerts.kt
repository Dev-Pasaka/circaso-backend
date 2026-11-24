package com.circaso.ramp.domain.support

import com.circaso.integrations.api.Email
import com.circaso.integrations.api.EmailGateway
import com.circaso.integrations.api.EmailTemplateIds
import com.circaso.integrations.api.IntegrationError
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.RampAmount
import com.circaso.ramp.domain.models.AlertFacts
import com.circaso.ramp.domain.models.AlertKind
import com.circaso.ramp.domain.models.AlertState
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.FeeSplit
import com.circaso.ramp.domain.models.MerchantAlert
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.Period
import com.circaso.ramp.domain.models.SignInContext
import com.circaso.ramp.domain.models.TopUp
import com.circaso.ramp.domain.models.TopUpState
import com.circaso.ramp.domain.models.Withdrawal
import com.circaso.ramp.domain.models.WithdrawalDestination
import com.circaso.ramp.domain.ports.LockLinks
import com.circaso.ramp.domain.repositories.MerchantAlertRepository
import com.circaso.ramp.domain.repositories.MerchantRepository
import com.circaso.ramp.domain.repositories.MerchantSecurityRepository
import com.circaso.ramp.domain.repositories.StatementRepository
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.Uuid7
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** How alerts word things: amounts with their unit and thousands, times in Nairobi time. */
object AlertText {
    private val TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'EAT'", Locale.ENGLISH)
    private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH)

    fun time(at: Instant): String = TIME.format(at.atOffset(Periods.MERCHANT_ZONE))

    fun day(day: LocalDate): String = DAY.format(day)

    fun money(amount: RampAmount): String {
        val format = DecimalFormat("#,##0.######", DecimalFormatSymbols(Locale.ENGLISH))
        return "${format.format(BigDecimal(amount.amount))} ${amount.unit}"
    }

    fun usdc(minor: Long): String = money(Amounts.usdc(minor))

    fun kes(minor: Long): String = money(Amounts.fiat(minor))

    fun network(network: Network): String = if (network == Network.MAINNET) "Base" else "Base Sepolia (testnet)"

    /** The merchant's words for why a withdrawal didn't go. */
    fun withdrawalFailure(reason: String?): String =
        when (reason) {
            "refused" -> "Circle refused the transfer"
            "float_short" -> "your float wallet didn't hold the USDC"
            "failed_on_chain" -> "the transaction failed on chain"
            "wallet_missing" -> "your float wallet couldn't be found"
            else -> "it couldn't be sent"
        }
}

/**
 * Writes the emails merchants get from events (2026-11-24). Each alert is a row written **in the same transaction** as
 * the change it reports, with a dedupe key naming the event (`withdrawal-sent-<id>`), so an event is one email however
 * often its code runs; [AlertSender] sends it. A security alert's lock link lasts [LINK_LIFETIME].
 */
class MerchantAlerts(private val rows: MerchantAlertRepository) {
    suspend fun raise(
        merchantId: MerchantId,
        kind: AlertKind,
        dedupeKey: String,
        facts: AlertFacts,
        at: Instant,
    ): Boolean =
        rows.insert(
            MerchantAlert(
                alertId = Uuid7.generate(at),
                merchantId = merchantId,
                kind = kind,
                dedupeKey = dedupeKey,
                facts = facts,
                state = AlertState.PENDING,
                attempts = 0,
                nextAttemptAt = at,
                createdAt = at,
                linkExpiresAt = if (kind.security) at.plus(LINK_LIFETIME) else null,
            ),
        )

    suspend fun signedIn(
        merchantId: MerchantId,
        method: String,
        context: SignInContext,
        at: Instant,
    ) = raise(
        merchantId,
        AlertKind.NEW_SIGN_IN,
        "sign-in-$merchantId-${at.toEpochMilli()}-${UUID.randomUUID()}",
        AlertFacts(
            time = AlertText.time(at),
            method = method,
            device = context.device,
            location = context.location,
        ),
        at,
    )

    suspend fun addressAdded(destination: WithdrawalDestination, at: Instant) =
        raise(
            destination.merchantId,
            AlertKind.ADDRESS_ADDED,
            "address-added-${destination.destinationId}",
            AlertFacts(
                time = AlertText.time(at),
                label = destination.label,
                address = destination.address,
                network = AlertText.network(destination.network),
                usableFrom = AlertText.time(destination.usableAt),
            ),
            at,
        )

    suspend fun addressRemoved(destination: WithdrawalDestination, at: Instant) =
        raise(
            destination.merchantId,
            AlertKind.ADDRESS_REMOVED,
            "address-removed-${destination.destinationId}",
            AlertFacts(
                time = AlertText.time(at),
                label = destination.label,
                address = destination.address,
                network = AlertText.network(destination.network),
            ),
            at,
        )

    suspend fun withdrawal(
        kind: AlertKind,
        withdrawal: Withdrawal,
        at: Instant,
        txHash: String? = withdrawal.txHash,
    ) = raise(
        withdrawal.merchantId,
        kind,
        "withdrawal-${kind.name.lowercase()}-${withdrawal.withdrawalId}",
        AlertFacts(
            time = AlertText.time(at),
            label = withdrawal.destinationLabel,
            address = withdrawal.destinationAddress,
            network = AlertText.network(withdrawal.network),
            amount = AlertText.usdc(withdrawal.amountMinor),
            txUrl = WithdrawalExplorer.url(withdrawal.blockchain, txHash) ?: "",
            reason = AlertText.withdrawalFailure(withdrawal.failureReason),
        ),
        at,
    )

    suspend fun pinChanged(merchantId: MerchantId, at: Instant) =
        raise(
            merchantId,
            AlertKind.PIN_CHANGED,
            "pin-changed-$merchantId-${at.toEpochMilli()}-${UUID.randomUUID()}",
            AlertFacts(AlertText.time(at)),
            at,
        )

    suspend fun accountChanged(
        merchantId: MerchantId,
        what: String,
        at: Instant,
    ) = raise(
        merchantId,
        AlertKind.PAYMENT_ACCOUNT_CHANGED,
        "account-changed-$merchantId-${at.toEpochMilli()}-${UUID.randomUUID()}",
        AlertFacts(AlertText.time(at), what = what),
        at,
    )

    suspend fun passkey(
        kind: AlertKind,
        merchantId: MerchantId,
        passkeyId: UUID,
        label: String,
        at: Instant,
    ) = raise(
        merchantId,
        kind,
        "${kind.name.lowercase()}-$passkeyId",
        AlertFacts(AlertText.time(at), label = label),
        at,
    )

    suspend fun locked(
        merchantId: MerchantId,
        cancelled: Int,
        at: Instant,
    ) = raise(
        merchantId,
        AlertKind.WITHDRAWALS_LOCKED,
        "locked-$merchantId-${at.toEpochMilli()}-${UUID.randomUUID()}",
        AlertFacts(AlertText.time(at), cancelled = cancelled.toString()),
        at,
    )

    /** A completed order the merchant filled: what came in, what went out, what they earned. */
    suspend fun orderCompleted(
        order: Order,
        split: FeeSplit,
        at: Instant,
    ) = when (order.direction) {
        Direction.ON_RAMP -> {
            raise(
                order.merchantId,
                AlertKind.BUY_COMPLETED,
                "order-completed-${order.orderId}",
                AlertFacts(
                    time = AlertText.time(at),
                    order = order.orderId.toString().takeLast(SHORT_ID),
                    received = AlertText.money(Amounts.fiat(order.fiat)),
                    paid = AlertText.usdc(order.assetMinor),
                    earned = AlertText.kes(split.merchantMinor),
                ),
                at,
            )
        }

        Direction.OFF_RAMP -> {
            raise(
                order.merchantId,
                AlertKind.SELL_COMPLETED,
                "order-completed-${order.orderId}",
                AlertFacts(
                    time = AlertText.time(at),
                    order = order.orderId.toString().takeLast(SHORT_ID),
                    received = AlertText.usdc(order.fundedMinor ?: order.assetMinor),
                    paid = AlertText.money(Amounts.fiat(order.fiat)),
                    earned = AlertText.kes(split.merchantMinor),
                ),
                at,
            )
        }
    }

    /** A top-up that settled one way or the other. */
    suspend fun topUp(topUp: TopUp, at: Instant) =
        raise(
            topUp.merchantId,
            AlertKind.TOPUP_RESULT,
            "topup-${topUp.topUpId}-${topUp.state.name.lowercase()}",
            AlertFacts(
                time = AlertText.time(at),
                wallet = topUp.target.label,
                amount = AlertText.kes(topUp.amountMinor),
                result =
                    when (topUp.state) {
                        TopUpState.CONFIRMED -> "Confirmed: the money is in the wallet"
                        TopUpState.FAILED -> "Not completed: nothing was taken"
                        TopUpState.UNKNOWN -> "Not confirmed yet: check the wallet's balance"
                        TopUpState.REQUESTED -> "Waiting"
                    },
            ),
            at,
        )

    companion object {
        /** How long a security email's "This wasn't me" link works. */
        val LINK_LIFETIME: Duration = Duration.ofHours(72)
        private const val SHORT_ID = 8
    }
}

/**
 * `ramp.merchant-alerts` (the app's process, every 15 s): sends the alerts that are due. Security alerts always go
 * and carry their lock link; activity ones go only while the merchant wants them ([MerchantSecurityRepository]),
 * else they are `SKIPPED`. The send's idempotency key is the alert id, so a retry after a lost answer delivers once.
 * An email whose template has no id yet ([templates]) is `SKIPPED` (`no_template`), as every other email of ours is
 * not sent then; one the provider refuses is `FAILED`; a provider out of reach is tried again (1, 5, 15, 60 minutes,
 * then every 2 hours, 8 attempts in all). Nothing logs an address or a link.
 */
class AlertSender(
    private val rows: MerchantAlertRepository,
    private val merchants: MerchantRepository,
    private val security: MerchantSecurityRepository,
    private val email: EmailGateway,
    private val templates: EmailTemplateIds?,
    private val links: LockLinks,
    private val settings: SettingsBook,
    private val clock: java.time.Clock,
) {
    suspend fun run(): Int {
        val due = rows.due(clock.instant(), BATCH)
        due.forEach { send(it) }
        return due.size
    }

    private suspend fun send(alert: MerchantAlert) {
        val now = clock.instant()
        val merchant = merchants.find(alert.merchantId) ?: return rows.skipped(alert.alertId, "no_merchant", now)
        if (alert.kind.activity) {
            val choices = security.find(alert.merchantId)
            val wanted = if (alert.kind == AlertKind.DAILY_SUMMARY) choices.dailySummary else choices.orderEmails
            if (!wanted) return rows.skipped(alert.alertId, "turned_off", now)
        }
        val lockUrl = if (alert.kind.security) lockUrl(alert.alertId) else null
        val message = emailOf(alert, lockUrl)
        // No template id set in the admin yet: this email isn't sent, as with every other email of ours.
        if (templates != null && templates.idOf(message.template).isNullOrBlank()) {
            return rows.skipped(alert.alertId, "no_template", now)
        }
        when (val sent = email.send(merchant.email, message, "ramp-alert-${alert.alertId}")) {
            is Resource.Success -> {
                rows.sent(alert.alertId, now)
            }

            is Resource.Error -> {
                val error = sent.error
                val attempts = alert.attempts + 1
                when {
                    error is IntegrationError.Unavailable && attempts < MAX_ATTEMPTS -> {
                        rows.retry(alert.alertId, attempts, now.plus(backoff(attempts)), "unavailable")
                    }

                    else -> {
                        rows.failed(alert.alertId, attempts, error.code)
                    }
                }
            }
        }
    }

    private suspend fun lockUrl(alertId: UUID): String? {
        val token = links.token(alertId) ?: return null
        val portal = settings.of(Network.MAINNET).merchantPortalUrl.trimEnd('/')
        // The token travels in the fragment: never sent to a server, never in a log or a Referer.
        return "$portal/lock#t=$token"
    }

    @Suppress("CyclomaticComplexMethod")
    private fun emailOf(alert: MerchantAlert, lockUrl: String?): Email {
        val f = alert.facts
        val time = f.time
        return when (alert.kind) {
            AlertKind.NEW_SIGN_IN -> {
                Email.MerchantNewSignIn(f.method.orEmpty(), f.device.orEmpty(), f.location.orEmpty(), time, lockUrl)
            }

            AlertKind.ADDRESS_ADDED -> {
                Email.MerchantAddressAdded(
                    f.label.orEmpty(),
                    f.address.orEmpty(),
                    f.network.orEmpty(),
                    f.usableFrom.orEmpty(),
                    time,
                    lockUrl,
                )
            }

            AlertKind.ADDRESS_REMOVED -> {
                Email.MerchantAddressRemoved(f.label.orEmpty(), f.address.orEmpty(), f.network.orEmpty(), time, lockUrl)
            }

            AlertKind.WITHDRAWAL_REQUESTED -> {
                Email.MerchantWithdrawalRequested(
                    f.amount.orEmpty(),
                    f.label.orEmpty(),
                    f.address.orEmpty(),
                    f.network.orEmpty(),
                    time,
                    lockUrl,
                )
            }

            AlertKind.WITHDRAWAL_SENT -> {
                Email.MerchantWithdrawalSent(
                    f.amount.orEmpty(),
                    f.label.orEmpty(),
                    f.address.orEmpty(),
                    f.txUrl.orEmpty(),
                    time,
                    lockUrl,
                )
            }

            AlertKind.WITHDRAWAL_COMPLETED -> {
                Email.MerchantWithdrawalCompleted(
                    f.amount.orEmpty(),
                    f.label.orEmpty(),
                    f.address.orEmpty(),
                    f.txUrl.orEmpty(),
                    time,
                    lockUrl,
                )
            }

            AlertKind.WITHDRAWAL_FAILED -> {
                Email.MerchantWithdrawalFailed(
                    f.amount.orEmpty(),
                    f.label.orEmpty(),
                    f.address.orEmpty(),
                    f.reason.orEmpty(),
                    time,
                    lockUrl,
                )
            }

            AlertKind.PIN_CHANGED -> {
                Email.MerchantPinChanged(time, lockUrl)
            }

            AlertKind.PAYMENT_ACCOUNT_CHANGED -> {
                Email.MerchantPaymentAccountChanged(f.what.orEmpty(), time, lockUrl)
            }

            AlertKind.PASSKEY_ADDED -> {
                Email.MerchantPasskeyAdded(f.label.orEmpty(), time, lockUrl)
            }

            AlertKind.PASSKEY_REMOVED -> {
                Email.MerchantPasskeyRemoved(f.label.orEmpty(), time, lockUrl)
            }

            AlertKind.WITHDRAWALS_LOCKED -> {
                Email.MerchantWithdrawalsLocked(f.cancelled ?: "0", time)
            }

            AlertKind.BUY_COMPLETED -> {
                Email.MerchantBuyCompleted(
                    f.order.orEmpty(),
                    f.received.orEmpty(),
                    f.paid.orEmpty(),
                    f.earned.orEmpty(),
                    time,
                )
            }

            AlertKind.SELL_COMPLETED -> {
                Email.MerchantSellCompleted(
                    f.order.orEmpty(),
                    f.received.orEmpty(),
                    f.paid.orEmpty(),
                    f.earned.orEmpty(),
                    time,
                )
            }

            AlertKind.TOPUP_RESULT -> {
                Email.MerchantTopUpResult(f.wallet.orEmpty(), f.amount.orEmpty(), f.result.orEmpty(), time)
            }

            AlertKind.DAILY_SUMMARY -> {
                Email.MerchantDailySummary(
                    f.day.orEmpty(),
                    f.orders.orEmpty(),
                    f.bought.orEmpty(),
                    f.sold.orEmpty(),
                    f.earned.orEmpty(),
                )
            }
        }
    }

    companion object {
        private const val BATCH = 50
        const val MAX_ATTEMPTS = 8
        private val BACKOFF = listOf(1L, 5L, 15L, 60L)

        fun backoff(attempts: Int): Duration = Duration.ofMinutes(BACKOFF.getOrNull(attempts - 1) ?: 120L)
    }
}

/**
 * `ramp.daily-summaries` (the app's process, hourly): for each merchant who asked for one, an email about yesterday
 * (a Nairobi day), once (`daily-<merchantId>-<day>`), only when they traded. Mainnet only, where merchants work.
 */
class DailySummaries(
    private val security: MerchantSecurityRepository,
    private val statements: StatementRepository,
    private val alerts: MerchantAlerts,
    private val clock: java.time.Clock,
) {
    suspend fun run(): Int {
        val now = clock.instant()
        val yesterday = Periods.merchantDay(now).minusDays(1)
        var written = 0
        security.withDailySummary().forEach { merchantId ->
            val totals = statements.totals(merchantId, Network.MAINNET, Period(yesterday, yesterday))
            if (totals.orders == 0) return@forEach
            val facts =
                AlertFacts(
                    time = AlertText.time(now),
                    day = AlertText.day(yesterday),
                    orders = totals.orders.toString(),
                    bought = "${AlertText.kes(totals.collectedMinor)} in, ${AlertText.usdc(totals.soldMinor)} out",
                    sold = "${AlertText.usdc(totals.boughtMinor)} in, ${AlertText.kes(totals.paidOutMinor)} out",
                    earned = AlertText.kes(totals.merchantMinor),
                )
            if (alerts.raise(merchantId, AlertKind.DAILY_SUMMARY, "daily-$merchantId-$yesterday", facts, now)) {
                written++
            }
        }
        return written
    }
}
