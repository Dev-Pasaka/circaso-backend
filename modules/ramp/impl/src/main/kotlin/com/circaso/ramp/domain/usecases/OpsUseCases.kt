package com.circaso.ramp.domain.usecases

import com.circaso.integrations.api.CollectionProviders
import com.circaso.integrations.api.KycProviders
import com.circaso.integrations.api.TreasuryCustody
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.RampOrderId
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.MerchantRecord
import com.circaso.ramp.domain.models.MerchantState
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.OrderView
import com.circaso.ramp.domain.models.PreviewAccess
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.RampState
import com.circaso.ramp.domain.models.SettingsChange
import com.circaso.ramp.domain.models.Subject
import com.circaso.ramp.domain.models.VerificationStatus
import com.circaso.ramp.domain.repositories.CredentialRepository
import com.circaso.ramp.domain.repositories.MerchantRepository
import com.circaso.ramp.domain.repositories.MerchantWalletRepository
import com.circaso.ramp.domain.repositories.OrderRepository
import com.circaso.ramp.domain.repositories.PreviewAccessRepository
import com.circaso.ramp.domain.repositories.SettingsRepository
import com.circaso.ramp.domain.repositories.VerificationRepository
import com.circaso.ramp.domain.support.FeeSweeps
import com.circaso.ramp.domain.support.FloatWallets
import com.circaso.ramp.domain.support.Presence
import com.circaso.ramp.domain.support.Releases
import com.circaso.ramp.domain.support.SettingsBook
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Clock

/**
 * `ramp.release` on one network, in the ops process: makes the wallets that are missing (the ramp's wallet set,
 * Circaso's ramp wallet, an approved merchant's float wallet), sends the USDC of every `PAID` order, and follows every
 * `RELEASING` one until the chain confirms it. Does nothing where this server has no treasury or can't sign.
 */
class ReleaseOrders(
    private val treasury: TreasuryCustody?,
    private val floatWallets: FloatWallets,
    private val releases: Releases,
    private val orders: OrderRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(network: Network): Int {
        val custody = treasury?.takeIf { it.canSign(network) } ?: return 0
        var handled = floatWallets.ensure(custody, network)
        // A moment in the future: every order in the state, however recently it got there.
        val all = clock.instant().plusSeconds(1)
        orders.stale(network, setOf(OrderState.PAID), all, LIMIT).forEach { order ->
            releases.release(custody, order)
            handled++
        }
        orders.stale(network, setOf(OrderState.RELEASING), all, LIMIT).forEach { order ->
            releases.follow(custody, order)
            handled++
        }
        return handled
    }

    private companion object {
        const val LIMIT = 100
    }
}

/** `ramp.settle-fees` on one network, in the ops process: the daily sweep of Circaso's share ([FeeSweeps]). */
class SettleFees(private val treasury: TreasuryCustody?, private val sweeps: FeeSweeps) {
    suspend operator fun invoke(network: Network): Int {
        val custody = treasury ?: return 0
        return sweeps.run(custody, network)
    }
}

/** RO1: merchants and their states, newest first. */
class ListMerchants(
    private val merchants: MerchantRepository,
    private val wallets: MerchantWalletRepository,
    private val credentials: CredentialRepository,
    private val verifications: VerificationRepository,
) {
    suspend operator fun invoke(before: MerchantId?, limit: Int): Resource<List<MerchantRecord>> =
        Resource.success(
            merchants.page(before, limit.coerceIn(1, MAX_LIMIT)).map {
                MerchantRecord(
                    merchant = it,
                    wallets = wallets.ofMerchant(it.merchantId),
                    paymentsConnected = credentials.find(it.merchantId) != null,
                    verifications = verifications.ofSubject(Subject.merchant(it.merchantId)),
                )
            },
        )

    private companion object {
        const val MAX_LIMIT = 200
    }
}

/**
 * RO2: approves ([approve]) or suspends a merchant. Approval takes one who applied (or was suspended); the ops
 * process then makes their float wallet within seconds. A suspension takes them offline at once.
 *
 * **Approval needs the merchant's identity verified** (`ramp.merchant_kyc_required` otherwise) on every network
 * whose ramp is open (`PREVIEW` or `ON`) and requires KYC, in the provider environment that network trusts. A
 * network that is `OFF` asks nothing yet: when it opens, the engine and RM6 still keep an unverified merchant from
 * trading there.
 */
class DecideMerchant(
    private val merchants: MerchantRepository,
    private val wallets: MerchantWalletRepository,
    private val credentials: CredentialRepository,
    private val presence: Presence,
    private val settings: SettingsBook,
    private val verifications: VerificationRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        merchantId: MerchantId,
        approve: Boolean,
        by: String,
    ): Resource<MerchantRecord> {
        val merchant = merchants.find(merchantId) ?: return Resource.error(RampError.MerchantNotFound)
        if (approve) {
            if (merchant.state == MerchantState.NEW) return Resource.error(RampError.MerchantNotReady)
            val unverified = unverifiedOn(merchantId)
            if (unverified.isNotEmpty()) {
                return Resource.error(RampError.MerchantKycRequired(unverified.joinToString(", ") { it.name }))
            }
            val from = setOf(MerchantState.APPLIED, MerchantState.SUSPENDED)
            merchants.setState(merchantId, MerchantState.APPROVED, from, by, clock.instant())
        } else {
            val from = MerchantState.entries.toSet() - MerchantState.SUSPENDED
            merchants.setState(merchantId, MerchantState.SUSPENDED, from, by, clock.instant())
            presence.goOffline(merchantId)
        }
        val after = merchants.find(merchantId) ?: return Resource.error(RampError.MerchantNotFound)
        return Resource.success(
            MerchantRecord(
                merchant = after,
                wallets = wallets.ofMerchant(merchantId),
                paymentsConnected = credentials.find(merchantId) != null,
                verifications = verifications.ofSubject(Subject.merchant(merchantId)),
            ),
        )
    }

    /** The open networks that require KYC on which the merchant isn't verified. */
    private suspend fun unverifiedOn(merchantId: MerchantId): List<Network> {
        val passed =
            verifications
                .ofSubject(Subject.merchant(merchantId))
                .filter { it.status == VerificationStatus.VERIFIED }
                .map { it.environment }
        return Network.entries.filter { network ->
            val current = settings.of(network)
            current.state != RampState.OFF && current.enforcesKyc && current.kycEnvironment !in passed
        }
    }
}

/** RO5: one network's ramp settings, read from the table now (never the ten-second copy). */
class GetSettings(private val rows: SettingsRepository, private val book: SettingsBook) {
    suspend operator fun invoke(network: Network): Resource<RampSettings> =
        Resource.success(rows.load()[network] ?: book.of(network))
}

/**
 * RO5: replaces one network's ramp settings, version-checked. What a constraint of the table would refuse is refused
 * here first, naming the field: the fee at most 5 %, the split at most the whole fee, KYC never off on mainnet, every
 * chain one of the network's own, a collections provider this server offers (so `log`, which reports every prompt
 * paid, can be chosen on a developer's machine only), a provider fee floor that isn't negative, and the identity
 * settings: a KYC provider this server offers, workflow ids that look like ids (or empty: that kind can't be verified
 * yet), the merchant portal's origin, and the daily cap and pending age within their ranges.
 */
class UpdateSettings(
    private val rows: SettingsRepository,
    private val book: SettingsBook,
    private val providers: CollectionProviders,
    private val kycProviders: KycProviders,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        settings: RampSettings,
        expectedVersion: Long?,
        by: String,
    ): Resource<SettingsChange> {
        invalid(settings)?.let { return Resource.error(it) }
        val before = rows.load()[settings.network] ?: book.of(settings.network)
        val after =
            rows.update(settings, expectedVersion, by, clock.instant())
                ?: return Resource.error(RampError.SettingsChanged)
        book.invalidate()
        return Resource.success(SettingsChange(before, after))
    }

    private fun invalid(s: RampSettings): RampError.SettingsInvalid? {
        val problem = moneyProblem(s) ?: kycProblem(s)
        return problem?.let { (field, what) -> RampError.SettingsInvalid(field, what) }
    }

    /** The identity settings: a provider this server offers, workflow ids that look like ids, the portal's origin. */
    private fun kycProblem(s: RampSettings): Pair<String, String>? =
        when {
            // Only what this server offers: `log` approves everybody, and is offered on a developer's machine alone.
            s.kycProvider !in kycProviders.names -> {
                "kycProvider" to "must be one of ${kycProviders.names.joinToString(", ")}"
            }

            !isWorkflowId(s.kycWorkflowUser) -> {
                "kycWorkflowUser" to "must be a workflow id, or empty"
            }

            !isWorkflowId(s.kycWorkflowMerchant) -> {
                "kycWorkflowMerchant" to "must be a workflow id, or empty"
            }

            !isOrigin(s.merchantPortalUrl) -> {
                "merchantPortalUrl" to "must be an origin only (https://merchants.circaso.com)"
            }

            s.kycAttemptsPerDay !in 1..MAX_KYC_ATTEMPTS -> {
                "kycAttemptsPerDay" to "must be 1 to $MAX_KYC_ATTEMPTS"
            }

            s.kycPendingHours !in 1..MAX_KYC_PENDING_HOURS -> {
                "kycPendingHours" to "must be 1 to $MAX_KYC_PENDING_HOURS"
            }

            else -> {
                null
            }
        }

    private fun isWorkflowId(id: String): Boolean = id.isEmpty() || WORKFLOW_ID.matches(id)

    private fun isOrigin(url: String): Boolean =
        (url.startsWith("https://") || url.startsWith("http://")) && !url.endsWith("/") &&
            url.none { it.isWhitespace() }

    private fun moneyProblem(s: RampSettings): Pair<String, String>? {
        val base = s.callbackBaseUrl
        return when {
            s.chains.isEmpty() -> {
                "chains" to "needs at least one chain"
            }

            s.chains.any { it.isBlank() || Network.ofChain(it) != s.network } -> {
                "chains" to
                    "must be on ${s.network}"
            }

            s.chains.distinct().size != s.chains.size -> {
                "chains" to "names a chain twice"
            }

            s.orderMinMinor <= 0 -> {
                "orderMinMinor" to "must be above zero"
            }

            s.orderMinMinor % WHOLE_SHILLING != 0L -> {
                "orderMinMinor" to "must be whole shillings"
            }

            s.orderMaxMinor < s.orderMinMinor -> {
                "orderMaxMinor" to "must be at least the minimum"
            }

            s.dailyLimitMinor < s.orderMinMinor -> {
                "dailyLimitMinor" to "must be at least the minimum"
            }

            s.promptsPerHour !in 0..MAX_PROMPTS -> {
                "promptsPerHour" to "must be 0 (no limit) to $MAX_PROMPTS"
            }

            s.feeBps !in 0..RampSettings.MAX_FEE_BPS -> {
                "feeBps" to "must be 0 to ${RampSettings.MAX_FEE_BPS}"
            }

            s.merchantShareBps !in 0..RampSettings.ALL_BPS -> {
                "merchantShareBps" to "must be 0 to 10000"
            }

            s.feeSettleHourUtc !in 0..LAST_HOUR -> {
                "feeSettleHourUtc" to "must be 0 to $LAST_HOUR"
            }

            s.quoteSeconds !in MIN_SECONDS..MAX_QUOTE_SECONDS -> {
                "quoteSeconds" to "must be 10 to 600"
            }

            s.paymentWindowSeconds !in MIN_WINDOW..MAX_WINDOW -> {
                "paymentWindowSeconds" to "must be 60 to 900"
            }

            !s.kycRequired && s.network == Network.MAINNET -> {
                "kycRequired" to "can't be off on MAINNET"
            }

            !(base.startsWith("https://") || base.startsWith("http://")) -> {
                "callbackBaseUrl" to "must be a URL"
            }

            base.endsWith("/") || base.any { it.isWhitespace() } -> {
                "callbackBaseUrl" to "must be an origin only"
            }

            // Only what this server offers: `log` reports every prompt paid, and is offered on a developer's
            // machine alone.
            s.collectionsProvider !in providers.names -> {
                "collectionsProvider" to "must be one of ${providers.names.joinToString(", ")}"
            }

            s.providerFeeFloorMinor !in 0..MAX_PROVIDER_FEE_FLOOR -> {
                "providerFeeFloorMinor" to "must be 0 (no check) to $MAX_PROVIDER_FEE_FLOOR"
            }

            else -> {
                null
            }
        }
    }

    private companion object {
        const val MAX_KYC_ATTEMPTS = 20

        /** Four weeks: the longest a provider's link may last. */
        const val MAX_KYC_PENDING_HOURS = 672
        val WORKFLOW_ID = Regex("[A-Za-z0-9_-]{8,100}")
        const val WHOLE_SHILLING = 100L
        const val MAX_PROMPTS = 60
        const val LAST_HOUR = 23
        const val MIN_SECONDS = 10
        const val MAX_QUOTE_SECONDS = 600
        const val MIN_WINDOW = 60
        const val MAX_WINDOW = 900

        /** KES 10 000: far above any provider's fees for a day of prompts; a larger floor is a typing mistake. */
        const val MAX_PROVIDER_FEE_FLOOR = 1_000_000L
    }
}

/** The collections providers this server offers, for staff to choose a network's from. */
class ListCollectionProviders(private val providers: CollectionProviders) {
    suspend operator fun invoke(): Resource<List<String>> = Resource.success(providers.names)
}

/** The KYC providers this server offers, for staff to choose a network's from. */
class ListKycProviders(private val providers: KycProviders) {
    suspend operator fun invoke(): Resource<List<String>> = Resource.success(providers.names)
}

/** Who may trade on a network while its ramp is in `PREVIEW`. */
class ListPreviewAccess(private val preview: PreviewAccessRepository) {
    suspend operator fun invoke(network: Network): Resource<List<PreviewAccess>> =
        Resource.success(preview.list(network, LIMIT))

    private companion object {
        const val LIMIT = 500
    }
}

/** Puts a person on a network's preview list, or takes them off; whether anything changed. */
class SetPreviewAccess(private val preview: PreviewAccessRepository, private val clock: Clock) {
    suspend operator fun invoke(
        userId: UserId,
        network: Network,
        enabled: Boolean,
        by: String,
    ): Resource<Boolean> =
        Resource.success(
            if (enabled) {
                preview.grant(PreviewAccess(userId, network, by, clock.instant()))
            } else {
                preview.revoke(userId, network)
            },
        )
}

/** RO4: the orders waiting for a person, oldest first. */
class ListReviewOrders(private val orders: OrderRepository, private val merchants: MerchantRepository) {
    suspend operator fun invoke(
        network: Network?,
        after: RampOrderId?,
        limit: Int,
    ): Resource<List<OrderView>> {
        val page: List<Order> = orders.inReview(network, after, limit.coerceIn(1, MAX_LIMIT))
        val names = page.map { it.merchantId }.distinct().associateWith { merchants.find(it)?.displayName.orEmpty() }
        return Resource.success(page.map { OrderView(it, names.getValue(it.merchantId)) })
    }

    private companion object {
        const val MAX_LIMIT = 200
    }
}
