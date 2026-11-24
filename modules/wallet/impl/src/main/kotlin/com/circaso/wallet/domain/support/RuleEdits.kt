package com.circaso.wallet.domain.support

import com.circaso.shared.FieldError
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.wallet.api.FeeRuleBand
import com.circaso.wallet.api.FeeRuleKind
import com.circaso.wallet.api.FeeRuleScope
import com.circaso.wallet.api.FeeRuleTerms
import com.circaso.wallet.api.LimitRuleScope
import com.circaso.wallet.api.LimitRuleTerms
import com.circaso.wallet.api.RuleStatus
import com.circaso.wallet.api.WalletOwnerType
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.FeeBand
import com.circaso.wallet.domain.models.FeeBounds
import com.circaso.wallet.domain.models.FeeKind
import com.circaso.wallet.domain.models.FeeRule
import com.circaso.wallet.domain.models.LimitRule
import com.circaso.wallet.domain.models.VersionWrite
import com.circaso.wallet.domain.models.VersionedRule
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * A rule's timeline under staff edits (decision 2026-11-08). Versions only ever add to the end: a change supersedes the
 * latest version at the new one's start, an end sets the latest version's end, and nothing starts in the past, so a
 * payment already priced can always be traced to the version that priced it.
 */
object RuleTimeline {
    /** How far ahead a change may be scheduled. */
    val MAX_AHEAD: Duration = Duration.ofDays(366)

    /**
     * Now, to the microsecond: what `timestamptz` keeps. PostgreSQL rounds a finer instant to the nearest microsecond,
     * so a nanosecond "now" (Linux clocks) could be stored a little later than it was compared, and a version written
     * to start now would not yet be in force. Every instant a rule edit writes or compares goes through here or [at].
     */
    fun now(clock: Clock): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    /** [requested] to the microsecond, or now when null; refused in the past or more than [MAX_AHEAD] away. */
    fun at(
        requested: Instant?,
        now: Instant,
        field: String,
    ): Resource<Instant> {
        val at = requested?.truncatedTo(ChronoUnit.MICROS) ?: return Resource.success(now)
        if (at.isBefore(now)) return Resource.error(WalletError.RuleInvalid(field, "can't be in the past"))
        if (at.isAfter(now.plus(MAX_AHEAD))) {
            return Resource.error(WalletError.RuleInvalid(field, "at most 366 days ahead"))
        }
        return Resource.success(at)
    }

    /**
     * The latest version, which a new version starting at [from] supersedes. It must still be the [baseVersion] the
     * caller edited, still live, and start before [from] (a version scheduled later is changed after it starts, never
     * in front of it); a scheduled end stays, carried by the new version.
     */
    fun <T : VersionedRule> superseded(
        versions: List<T>,
        baseVersion: Int,
        from: Instant,
        now: Instant,
    ): Resource<T> {
        val latest =
            when (val found = latest(versions, baseVersion, now)) {
                is Resource.Success -> found.data
                is Resource.Error -> return found
            }
        if (!from.isAfter(latest.effectiveFrom)) {
            return Resource.error(WalletError.RuleChangePending(latest.effectiveFrom))
        }
        val end = latest.effectiveTo
        if (end != null && !from.isBefore(end)) {
            return Resource.error(WalletError.RuleInvalid("effectiveFrom", "must be before the rule ends at $end"))
        }
        return Resource.success(latest)
    }

    /** The latest version, which an end at [to] closes. */
    fun <T : VersionedRule> ended(
        versions: List<T>,
        baseVersion: Int,
        to: Instant,
        now: Instant,
    ): Resource<T> {
        val latest =
            when (val found = latest(versions, baseVersion, now)) {
                is Resource.Success -> found.data
                is Resource.Error -> return found
            }
        if (!to.isAfter(latest.effectiveFrom)) {
            return Resource.error(WalletError.RuleChangePending(latest.effectiveFrom))
        }
        return Resource.success(latest)
    }

    /** Where a rule stands at [at], and the version in force then (the latest started one, unless it has ended). */
    fun <T : VersionedRule> status(versions: List<T>, at: Instant): Pair<RuleStatus, Int?> {
        val current = versions.filter { !it.effectiveFrom.isAfter(at) }.maxByOrNull { it.version }
        if (current != null && current.inForce(at)) return RuleStatus.ACTIVE to current.version
        val later = versions.any { it.effectiveFrom.isAfter(at) }
        return (if (later) RuleStatus.SCHEDULED else RuleStatus.ENDED) to null
    }

    /** Whether the rule still applies now or later: its latest version hasn't ended. */
    fun <T : VersionedRule> isLive(versions: List<T>, now: Instant): Boolean =
        versions.maxByOrNull { it.version }?.liveAt(now) == true

    private fun <T : VersionedRule> latest(
        versions: List<T>,
        baseVersion: Int,
        now: Instant,
    ): Resource<T> {
        val latest = versions.maxByOrNull { it.version } ?: return Resource.error(WalletError.RuleNotFound)
        if (latest.version != baseVersion) return Resource.error(WalletError.RuleChanged)
        if (!latest.liveAt(now)) return Resource.error(WalletError.RuleEnded)
        return Resource.success(latest)
    }
}

/**
 * The business checks on what staff write into a rule. The database checks the same (and the bounds trigger), but a
 * caller gets every problem at once, named by field, before anything is written.
 */
object RuleChecks {
    private const val BPS_MAX = 10_000
    private const val BANDS_MAX = 20

    /** A trillion dollars in micro-dollars: far above any cap, far below `numeric(20,6)` and a Long. */
    private const val USD_MICROS_MAX = 1_000_000_000_000_000_000L
    private const val WINDOW_HOURS_MAX = 24 * 31
    private const val NOTE_MIN = 3
    private const val NOTE_MAX = 500
    private val CHAIN = Regex("[A-Z0-9][A-Z0-9-]{0,39}")
    private val ASSET = Regex("[A-Z0-9][A-Z0-9.]{0,14}")
    private const val SPACES_CLOSED = "spaces are closed; a rule can't be for a space wallet"

    fun note(raw: String): Resource<String> {
        val note = raw.trim()
        if (note.length !in NOTE_MIN..NOTE_MAX) {
            return Resource.error(WalletError.RuleInvalid("note", "say why, in $NOTE_MIN to $NOTE_MAX characters"))
        }
        return Resource.success(note)
    }

    /**
     * The terms as they are stored: with bands (2026-11-24), [FeeRuleTerms.rateBps] becomes the first band's rate, and
     * a single band from 0 with no end is the flat rate itself (no bands kept).
     */
    fun fee(terms: FeeRuleTerms): Resource<FeeRuleTerms> {
        val problems =
            buildList {
                if (terms.bands.isEmpty() && terms.rateBps !in 0..BPS_MAX) {
                    add(FieldError("rateBps", "must be 0 to $BPS_MAX"))
                }
                addAll(bands(terms.bands))
                if (terms.capBaseUsdMicros !in 1..USD_MICROS_MAX) {
                    add(FieldError("capBaseUsdMicros", "must be more than 0"))
                }
                terms.minFeeUsdMicros?.let { min ->
                    if (min !in 0..USD_MICROS_MAX) add(FieldError("minFeeUsdMicros", "can't be negative"))
                }
                // Deprecated (2026-11-08): kept and stored, but no longer prices anything.
                if (terms.gasShareMaxBps !in 1..BPS_MAX) add(FieldError("gasShareMaxBps", "must be 1 to $BPS_MAX"))
                if (terms.gasMarginBps !in 0..BPS_MAX) add(FieldError("gasMarginBps", "must be 0 to $BPS_MAX"))
            }
        if (problems.isNotEmpty()) return Resource.error(WalletError.RuleInvalid(problems))
        val first = terms.bands.firstOrNull() ?: return Resource.success(terms)
        val flat = terms.bands.size == 1
        return Resource.success(terms.copy(rateBps = first.rateBps, bands = if (flat) emptyList() else terms.bands))
    }

    /**
     * What is wrong with a band list, by field (`bands[2].toUsdMicros`): it starts at 0, each band ends where the next
     * starts and above where it starts (no gap, no overlap, no empty band), only the last is open-ended, every rate is
     * 0 to 10 000 bps, and there are at most [BANDS_MAX]. Empty is fine: the flat rate.
     */
    fun bands(bands: List<FeeRuleBand>): List<FieldError> =
        buildList {
            if (bands.isEmpty()) return@buildList
            if (bands.size > BANDS_MAX) add(FieldError("bands", "at most $BANDS_MAX bands"))
            if (bands.first().fromUsdMicros !=
                0L
            ) {
                add(FieldError("bands[0].fromUsdMicros", "the first band starts at 0"))
            }
            bands.forEachIndexed { index, band ->
                val field = "bands[$index]"
                val next = bands.getOrNull(index + 1)
                val to = band.toUsdMicros
                if (band.rateBps !in 0..BPS_MAX) add(FieldError("$field.rateBps", "must be 0 to $BPS_MAX"))
                when {
                    next == null && to != null -> {
                        add(FieldError("$field.toUsdMicros", "the last band has no end"))
                    }

                    next != null && to == null -> {
                        add(FieldError("$field.toUsdMicros", "only the last band has no end"))
                    }

                    to != null && to <= band.fromUsdMicros -> {
                        add(FieldError("$field.toUsdMicros", "must be above from"))
                    }

                    to != null && to > USD_MICROS_MAX -> {
                        add(FieldError("$field.toUsdMicros", "is too large"))
                    }

                    next != null && next.fromUsdMicros != to -> {
                        add(FieldError("bands[${index + 1}].fromUsdMicros", "must be where the band before ends"))
                    }
                }
            }
        }

    fun rate(rateBps: Int, bounds: FeeBounds): Resource<Unit> =
        if (bounds.allows(rateBps)) {
            Resource.success(Unit)
        } else {
            Resource.error(WalletError.FeeRateOutOfBounds(bounds.minRateBps, bounds.maxRateBps))
        }

    /** Every rate the rule can charge, each band's, within [bounds]. */
    fun rates(rule: FeeRule, bounds: FeeBounds): Resource<Unit> =
        rule.rates.firstOrNull { !bounds.allows(it) }?.let { rate(it, bounds) } ?: Resource.success(Unit)

    /** The scope with its chain and coin in the form the payments carry (upper case), or what is wrong with it. */
    fun feeScope(scope: FeeRuleScope): Resource<FeeRuleScope> {
        val chain =
            scope.blockchain
                ?.trim()
                ?.uppercase()
                ?.ifEmpty { null }
        val asset =
            scope.asset
                ?.trim()
                ?.uppercase()
                ?.ifEmpty { null }
        val problems =
            buildList {
                if (chain != null && !CHAIN.matches(chain)) add(FieldError("blockchain", "isn't a chain name"))
                if (asset != null && !ASSET.matches(asset)) add(FieldError("asset", "isn't a coin symbol"))
                if (chain != null && scope.network != null && Network.ofChain(chain) != scope.network) {
                    add(FieldError("blockchain", "isn't a ${scope.network} chain"))
                }
                // Spaces were archived on 2026-11-05: no new rule may price a space wallet.
                if (scope.senderType == WalletOwnerType.SPACE) add(FieldError("senderType", SPACES_CLOSED))
                if (scope.receiverType == WalletOwnerType.SPACE) add(FieldError("receiverType", SPACES_CLOSED))
                if (scope.kind == FeeRuleKind.EXTERNAL && scope.receiverType != null) {
                    add(FieldError("receiverType", "an outside address has no receiver type"))
                }
            }
        if (problems.isNotEmpty()) return Resource.error(WalletError.RuleInvalid(problems))
        return Resource.success(scope.copy(blockchain = chain, asset = asset))
    }

    fun limit(terms: LimitRuleTerms): Resource<LimitRuleTerms> {
        val problems =
            buildList {
                terms.perPaymentMaxUsdMicros?.let {
                    if (it !in 1..USD_MICROS_MAX) add(FieldError("perPaymentMaxUsdMicros", "must be more than 0"))
                }
                terms.dailyMaxUsdMicros?.let {
                    if (it !in 1..USD_MICROS_MAX) add(FieldError("dailyMaxUsdMicros", "must be more than 0"))
                }
                if (terms.windowHours !in 1..WINDOW_HOURS_MAX) {
                    add(FieldError("windowHours", "must be 1 to $WINDOW_HOURS_MAX"))
                }
            }
        return if (problems.isEmpty()) Resource.success(terms) else Resource.error(WalletError.RuleInvalid(problems))
    }

    fun limitScope(scope: LimitRuleScope): Resource<LimitRuleScope> {
        val asset =
            scope.asset
                ?.trim()
                ?.uppercase()
                ?.ifEmpty { null }
        if (asset != null && !ASSET.matches(asset)) {
            return Resource.error(WalletError.RuleInvalid("asset", "isn't a coin symbol"))
        }
        return Resource.success(scope.copy(asset = asset))
    }

    /** What a version write's outcome means to the caller. */
    fun written(outcome: VersionWrite, bounds: FeeBounds? = null): Resource<Unit> =
        when (outcome) {
            VersionWrite.WRITTEN -> {
                Resource.success(Unit)
            }

            VersionWrite.TAKEN -> {
                Resource.error(WalletError.RuleChanged)
            }

            VersionWrite.OUT_OF_BOUNDS -> {
                Resource.error(WalletError.FeeRateOutOfBounds(bounds?.minRateBps ?: 0, bounds?.maxRateBps ?: 0))
            }
        }
}

/** The scope of a fee rule version, to compare rules: two live rules may not cover exactly the same payments. */
fun FeeRule.scope(): FeeRuleScope =
    FeeRuleScope(
        kind = kind?.let { FeeRuleKind.valueOf(it.name) },
        senderType = senderType,
        receiverType = receiverType,
        blockchain = blockchain,
        asset = asset,
        walletId = walletId,
        network = network,
    )

fun FeeRuleKind.toFeeKind(): FeeKind = FeeKind.valueOf(name)

fun LimitRule.scope(): LimitRuleScope = LimitRuleScope(ownerType, walletId, asset, network)

fun LimitRule.terms(): LimitRuleTerms = LimitRuleTerms(perPaymentMaxMinor, dailyMaxMinor, window.toHours().toInt())

/** The bands staff wrote, as the domain keeps them. */
fun FeeRuleTerms.feeBands(): List<FeeBand> = bands.map { FeeBand(it.fromUsdMicros, it.toUsdMicros, it.rateBps) }

/** A rule's bands as the staff contract gives them: always at least one, the flat rate being one open band. */
fun FeeRule.ruleBands(): List<FeeRuleBand> = effectiveBands.map { FeeRuleBand(it.fromMinor, it.toMinor, it.rateBps) }
