package com.circaso.wallet

import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.api.FeeRuleKind
import com.circaso.wallet.api.FeeRuleScope
import com.circaso.wallet.api.FeeRuleTerms
import com.circaso.wallet.api.LimitRuleTerms
import com.circaso.wallet.api.RuleStatus
import com.circaso.wallet.api.WalletOwnerType
import com.circaso.wallet.data.repositories.DatabaseTransactor
import com.circaso.wallet.data.repositories.FeeLedgerRepositoryImpl
import com.circaso.wallet.data.repositories.FeeQuoteRepositoryImpl
import com.circaso.wallet.data.repositories.RuleAdminRepositoryImpl
import com.circaso.wallet.data.repositories.RuleRepositoryImpl
import com.circaso.wallet.data.repositories.TransactionRepositoryImpl
import com.circaso.wallet.data.repositories.TransferRepositoryImpl
import com.circaso.wallet.data.repositories.WalletOutboxImpl
import com.circaso.wallet.data.repositories.WalletRepositoryImpl
import com.circaso.wallet.di.walletDatabaseSpec
import com.circaso.wallet.domain.models.GasSource
import com.circaso.wallet.domain.models.Payee
import com.circaso.wallet.domain.models.TransferCommand
import com.circaso.wallet.domain.support.RuleTimeline
import com.circaso.wallet.domain.support.RuleVersions
import com.circaso.wallet.domain.usecases.ChangeFeeRule
import com.circaso.wallet.domain.usecases.ChangeLimitRule
import com.circaso.wallet.domain.usecases.CreateFeeRule
import com.circaso.wallet.domain.usecases.EndFeeRule
import com.circaso.wallet.domain.usecases.EndLimitRule
import com.circaso.wallet.domain.usecases.ListFeeRules
import com.circaso.wallet.domain.usecases.UpdateFeeBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Staff edits of fees and limits on PostgreSQL (decision 2026-11-08): a change is a new version and the old one ends
 * where it starts, two racing edits can't both land, the bounds hold both ways, nothing starts in the past, and the
 * very next quote prices with the new version while a quote already shown keeps its fee.
 */
class FeeAdminDataTest {
    private val database: Database = TestPostgres.database(walletDatabaseSpec)
    private val rules = RuleRepositoryImpl(database)
    private val admin = RuleAdminRepositoryImpl(database)
    private val transactor = DatabaseTransactor(database)

    /**
     * Linux clocks give nanoseconds; `timestamptz` keeps microseconds and rounds to the nearest. A "now" whose last
     * digits round up (…991677 ns → …992 µs) is what broke CI: this clock always has them, so a Mac can't hide it.
     */
    private val clock = MutableClock(Instant.now().truncatedTo(ChronoUnit.MILLIS).plusNanos(991_677))

    /** What the database holds for "now". */
    private fun stored(at: Instant): Instant = at.truncatedTo(ChronoUnit.MICROS)

    private val change = ChangeFeeRule(admin, transactor, clock)
    private val end = EndFeeRule(admin, transactor, clock)
    private val create = CreateFeeRule(rules, admin, transactor, clock)
    private val bounds = UpdateFeeBounds(rules, admin, transactor, clock)
    private val list = ListFeeRules(rules, admin, transactor, clock)
    private val changeLimit = ChangeLimitRule(admin, transactor, clock)
    private val endLimit = EndLimitRule(admin, transactor, clock)

    @BeforeTest
    fun clean() =
        runBlocking<Unit> {
            TestPostgres.truncate("wallet")
            FeeDataTest.seeds().forEach(::sql)
            sql("UPDATE wallet.fee_rules SET effective_from = now() - interval '1 hour'")
            sql("UPDATE wallet.limit_rules SET effective_from = now() - interval '1 hour'")
            sql("UPDATE wallet.fee_bounds SET min_rate_bps = 0, max_rate_bps = 200")
        }

    private fun sql(statement: String) {
        TestPostgres.superuser { connection -> connection.createStatement().use { it.execute(statement) } }
    }

    private fun terms(rateBps: Int, minFeeUsdMicros: Long? = null) =
        FeeRuleTerms(rateBps, 10_000_000_000, minFeeUsdMicros, 2_500, 2_000)

    private fun inForce(ruleId: UUID, at: Instant = clock.instant()) =
        runBlocking { RuleVersions.currentFees(rules.feeRules(), at).singleOrNull { it.ruleId == ruleId } }

    @Test
    fun `a change is a new version, and the old one ends exactly where it starts`() =
        runBlocking<Unit> {
            val changed = change(BETWEEN_PEOPLE, 1, terms(75, 100_000), null, "Q4 pricing").data()

            val versions = admin.feeVersions(BETWEEN_PEOPLE).sortedBy { it.version }
            assertEquals(listOf(1, 2), versions.map { it.version })
            assertEquals(50, versions[0].rateBps, "the old version is never overwritten")
            assertEquals(stored(clock.instant()), versions[0].effectiveTo)
            assertEquals(stored(clock.instant()), versions[1].effectiveFrom)
            assertEquals(versions[0].effectiveTo, versions[1].effectiveFrom, "no gap, no overlap")
            assertNull(versions[1].effectiveTo)
            assertEquals("Q4 pricing", versions[1].note)
            assertEquals("INTERNAL", versions[1].kind?.name, "the scope stays the rule's")
            assertEquals(1, changed.before?.version)
            assertEquals(75, inForce(BETWEEN_PEOPLE)?.rateBps)

            val history = list().data()
            val (status, current) =
                RuleTimeline.status(
                    history.rules.filter { it.ruleId == BETWEEN_PEOPLE },
                    history.at,
                )
            assertEquals(RuleStatus.ACTIVE to 2, status to current)
        }

    @Test
    fun `two staff changing the same version at once - exactly one lands`() =
        runBlocking<Unit> {
            val results =
                coroutineScope {
                    listOf(60, 70)
                        .map { rate ->
                            async(Dispatchers.Default) { change(BETWEEN_PEOPLE, 1, terms(rate), null, "Race $rate") }
                        }.awaitAll()
                }

            assertEquals(1, results.count { it is Resource.Success })
            assertEquals(
                listOf("wallet.rule_changed"),
                results.filterIsInstance<Resource.Error>().map { it.error.code },
            )
            assertEquals(2, admin.feeVersions(BETWEEN_PEOPLE).size)
            val stale = change(BETWEEN_PEOPLE, 1, terms(80), null, "From an old screen")
            assertEquals("wallet.rule_changed", (stale as Resource.Error).error.code)
        }

    @Test
    fun `a rate outside the bounds is refused, and bounds can't exclude a rule in force`() =
        runBlocking<Unit> {
            val tooHigh = change(BETWEEN_PEOPLE, 1, terms(250), null, "Try 2.5 %")
            assertEquals("wallet.fee_rate_out_of_bounds", (tooHigh as Resource.Error).error.code)
            assertEquals(1, admin.feeVersions(BETWEEN_PEOPLE).size, "nothing written")

            val excluding = bounds(0, 150)
            assertEquals("wallet.fee_bounds_exclude_rules", (excluding as Resource.Error).error.code)
            assertTrue(
                excluding.error.details!!
                    .fields
                    .any { it.problem == EXTERNAL.toString() },
                "the 2 % rule",
            )
            assertEquals(200, admin.feeBounds().maxRateBps)

            val widened = bounds(0, 300).data()
            assertEquals(200 to 300, widened.before.maxRateBps to widened.after.maxRateBps)
            assertIs<Resource.Success<*>>(change(BETWEEN_PEOPLE, 1, terms(250), null, "Now within bounds"))
        }

    @Test
    fun `a scheduled change waits for its time, and nothing starts in the past`() =
        runBlocking<Unit> {
            val past = change(BETWEEN_PEOPLE, 1, terms(75), clock.instant().minusSeconds(1), "Backdated")
            assertEquals("wallet.rule_invalid", (past as Resource.Error).error.code)

            val tomorrow = clock.instant().plus(Duration.ofDays(1))
            change(BETWEEN_PEOPLE, 1, terms(75), tomorrow, "From tomorrow").data()

            assertEquals(50, inForce(BETWEEN_PEOPLE)?.rateBps, "still the old rate today")
            assertEquals(75, inForce(BETWEEN_PEOPLE, tomorrow)?.rateBps)
            val inFront = change(BETWEEN_PEOPLE, 2, terms(90), clock.instant().plusSeconds(60), "Before it")
            assertEquals("wallet.rule_change_pending", (inFront as Resource.Error).error.code)
            assertIs<Resource.Success<*>>(change(BETWEEN_PEOPLE, 2, terms(90), tomorrow.plusSeconds(60), "After it"))
        }

    @Test
    fun `ending a rule sets its end and keeps every version, and an ended rule can't change`() =
        runBlocking<Unit> {
            val ended = end(EXTERNAL, 1, null).data()

            assertEquals(stored(clock.instant()), ended.after.effectiveTo)
            assertEquals(ended.after.effectiveTo, admin.feeVersions(EXTERNAL).single().effectiveTo, "as written")
            assertNull(inForce(EXTERNAL))
            assertEquals(1, admin.feeVersions(EXTERNAL).size)
            val again = change(EXTERNAL, 1, terms(150), null, "Revive")
            assertEquals("wallet.rule_ended", (again as Resource.Error).error.code)
        }

    @Test
    fun `a new rule can't duplicate a live rule's scope, and starts as version 1`() =
        runBlocking<Unit> {
            val external = FeeRuleScope(FeeRuleKind.EXTERNAL, null, null, null, null, null)
            val duplicate = create(external, terms(100), null, "Same as the 2 % rule")
            assertEquals("wallet.rule_scope_taken", (duplicate as Resource.Error).error.code)

            val base = external.copy(blockchain = "base-sepolia")
            val made = create(base, terms(100), null, "Base launch price").data()
            assertEquals(1, made.after.version)
            assertEquals("BASE-SEPOLIA", made.after.blockchain, "kept as payments carry it")
            assertEquals(100, inForce(made.after.ruleId)?.rateBps)
        }

    @Test
    fun `limits version the same way`() =
        runBlocking<Unit> {
            val raised = changeLimit(PERSON_LIMITS, 1, LimitRuleTerms(1_000_000_000, 5_000_000_000, 24), null, "KYC 1")

            assertEquals(1_000_000_000, raised.data().after.perPaymentMaxMinor)
            val current = RuleVersions.currentLimits(rules.limitRules(), clock.instant())
            assertEquals(5_000_000_000, current.single { it.ownerType == WalletOwnerType.PERSON }.dailyMaxMinor)
            assertEquals("wallet.rule_changed", (endLimit(PERSON_LIMITS, 1, null) as Resource.Error).error.code)
            clock.advance(Duration.ofSeconds(1))
            assertIs<Resource.Success<*>>(endLimit(PERSON_LIMITS, 2, null))
            val after = RuleVersions.currentLimits(rules.limitRules(), clock.instant())
            assertTrue(after.none { it.ruleId == PERSON_LIMITS }, "ended: no personal limit in force")
        }

    @Test
    fun `the next quote prices with the new version, while a quote already shown keeps its fee`() =
        runBlocking<Unit> {
            val h =
                Harness(
                    WalletRepositoryImpl(database),
                    transactor,
                    WalletOutboxImpl(database),
                    TransferRepositoryImpl(database),
                    TransactionRepositoryImpl(database),
                    ruleRows = rules,
                    quoteRows = FeeQuoteRepositoryImpl(database),
                    ledger = FeeLedgerRepositoryImpl(database),
                ).also { it.clock.now = clock.instant() }
            val (amina, bob) = UserId.new() to UserId.new()
            h.fund(amina, 300_000_000)
            h.walletOn(bob)
            h.reach.connect(amina, bob)

            val shown = h.quote(amina, Payee.Person(bob), 100_000_000).data()
            change(BETWEEN_PEOPLE, 1, terms(100), null, "1 % between people").data()
            val next = h.quote(amina, Payee.Person(bob), 100_000_000).data()

            // Gas inclusive: 1 cent of network cost with the 20 % margin is 2 cents, on top of the rate.
            assertEquals(BigInteger.valueOf(520_000), shown.fee.amountMinor, "0.5 % when it was shown")
            assertEquals(BigInteger.valueOf(1_020_000), next.fee.amountMinor, "1 % from the next quote on")
            assertEquals(2, next.fee.ruleVersion)
            val paid =
                h
                    .prepareTransfer(
                        TransferCommand(
                            amina,
                            "BASE-SEPOLIA",
                            Payee.Person(bob),
                            Money(100_000_000, Asset.USDC),
                            null,
                            null,
                            UUID.randomUUID(),
                            quoteId = shown.quoteId,
                        ),
                    ).data()
                    .transfer
            assertEquals(BigInteger.valueOf(520_000), paid.feeMinor, "the quote shown is the fee charged")
        }

    @Test
    fun `after a change to 0 percent with no margin the next quote charges the network cost alone`() =
        runBlocking<Unit> {
            val h = quoting()
            val (amina, bob) = UserId.new() to UserId.new()
            h.fund(amina, 300_000_000)
            h.walletOn(bob)
            h.reach.connect(amina, bob)

            change(BETWEEN_PEOPLE, 1, FeeRuleTerms(0, 100_000_000, null, 2_500, 0), null, "promotion").data()
            val quote = h.quote(amina, Payee.Person(bob), 7_700_000).data()

            assertEquals(BigInteger.valueOf(10_000), quote.fee.amountMinor, "the 1 cent network cost, passed through")
            assertEquals(0, quote.fee.rateBps)
            assertEquals(false, quote.fee.minimum)
            assertEquals(2, quote.fee.ruleVersion)
            assertEquals(GasSource.ESTIMATE, quote.fee.gasSource, "no history in an empty ledger")
        }

    @Test
    fun `the space rules are ended by the migration and a new space rule is refused`() =
        runBlocking<Unit> {
            FeeDataTest.statements("V10__fee_gas_inclusive.sql", "UPDATE").forEach(::sql)
            val spaces = listOf(SPACE_FROM, SPACE_TO)
            assertTrue(spaces.all { inForce(it, Instant.now().plusSeconds(1)) == null }, "no space rule in force")
            assertEquals(1, admin.feeVersions(SPACE_FROM).size, "the version is kept, only ended")
            val refused =
                create(
                    FeeRuleScope(FeeRuleKind.INTERNAL, WalletOwnerType.SPACE, null, null, null, null),
                    terms(100),
                    null,
                    "space pricing",
                )
            assertEquals("wallet.rule_invalid", (refused as Resource.Error).error.code)
            assertEquals(
                listOf("senderType"),
                refused.error.details
                    ?.fields
                    ?.map { it.field },
            )
            val people =
                create(
                    FeeRuleScope(FeeRuleKind.INTERNAL, WalletOwnerType.PERSON, null, null, null, null),
                    terms(20),
                    null,
                    "people",
                )
            assertIs<Resource.Success<*>>(people)
        }

    private fun quoting() =
        Harness(
            WalletRepositoryImpl(database),
            transactor,
            WalletOutboxImpl(database),
            TransferRepositoryImpl(database),
            TransactionRepositoryImpl(database),
            ruleRows = rules,
            quoteRows = FeeQuoteRepositoryImpl(database),
            ledger = FeeLedgerRepositoryImpl(database),
        ).also { it.clock.now = clock.instant() }

    private companion object {
        val BETWEEN_PEOPLE: UUID = UUID.fromString("0195f2f0-0000-7000-8000-000000000001")
        val EXTERNAL: UUID = UUID.fromString("0195f2f0-0000-7000-8000-000000000006")
        val SPACE_FROM: UUID = UUID.fromString("0195f2f0-0000-7000-8000-000000000004")
        val SPACE_TO: UUID = UUID.fromString("0195f2f0-0000-7000-8000-000000000005")
        val PERSON_LIMITS: UUID = UUID.fromString("0195f2f0-0000-7000-8000-000000000101")
    }
}
