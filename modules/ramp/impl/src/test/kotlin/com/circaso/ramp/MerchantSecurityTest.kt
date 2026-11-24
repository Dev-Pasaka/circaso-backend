package com.circaso.ramp

import com.circaso.integrations.api.CollectionCredentials
import com.circaso.integrations.api.Email
import com.circaso.integrations.api.EmailTemplate
import com.circaso.integrations.api.EmailTemplateIds
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.TreasuryTransactionState
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.AlertKind
import com.circaso.ramp.domain.models.AlertState
import com.circaso.ramp.domain.models.DestinationCommand
import com.circaso.ramp.domain.models.OfflineReason
import com.circaso.ramp.domain.models.Passkey
import com.circaso.ramp.domain.models.PasskeyAssertion
import com.circaso.ramp.domain.models.PaymentAccountCommand
import com.circaso.ramp.domain.models.RemovalProof
import com.circaso.ramp.domain.models.SecurityCodePurpose
import com.circaso.ramp.domain.models.SignInContext
import com.circaso.ramp.domain.models.StepUpBinding
import com.circaso.ramp.domain.models.WithdrawCommand
import com.circaso.ramp.domain.models.Withdrawal
import com.circaso.ramp.domain.models.WithdrawalDestination
import com.circaso.ramp.domain.models.WithdrawalState
import com.circaso.ramp.domain.support.AlertSender
import com.circaso.ramp.domain.support.MerchantAlerts
import com.circaso.ramp.domain.support.WithdrawalSends
import com.circaso.shared.DomainError
import com.circaso.shared.Resource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Merchant security (2026-11-24): approved addresses only; passkeys (registration with a code and the PIN, sign-in,
 * the step-up bound to the exact request, single use, expiry, replay); alerts written with their event and sent once;
 * and the "This wasn't me" lock (single use, expiry, cancels only unclaimed withdrawals).
 */
class MerchantSecurityTest {
    private val ramp = Harness()
    private val crynce = ramp.crynce.merchantId
    private val pin = "482913"
    private val address = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    private val key = TestAuthenticator()

    private fun <T> Resource<T>.error(): DomainError = (this as Resource.Error).error

    private fun <T> Resource<T>.data(): T = (this as Resource.Success).data

    private suspend fun withPin() {
        val account = PaymentAccountCommand(CollectionCredentials("Crynce-user", "Crynce-pass", "911"), pin)
        assertIs<Resource.Success<*>>(ramp.storePayments(crynce, TESTNET, account))
    }

    private suspend fun code(purpose: SecurityCodePurpose): String {
        ramp.clock.advance(61) // one code a minute per purpose
        assertIs<Resource.Success<*>>(ramp.merchantPasskeys.sendCode(crynce, purpose, "203.0.113.9"))
        return (
            ramp.email.sent
                .last()
                .second as Email.MerchantSecurityCode
        ).code
    }

    /** Adds a passkey the way the portal does: code, PIN, options, the authenticator's answer. */
    private suspend fun register(authenticator: TestAuthenticator = key, label: String = "MacBook"): Passkey {
        val options =
            ramp.merchantPasskeys.startRegistration(crynce, label, code(SecurityCodePurpose.ADD_PASSKEY), pin).data()
        return ramp.merchantPasskeys
            .finishRegistration(
                crynce,
                options.registrationId,
                authenticator.create(options),
            ).data()
    }

    private suspend fun prove(binding: StepUpBinding, authenticator: TestAuthenticator = key): PasskeyAssertion =
        authenticator.answer(ramp.stepUp.challenge(binding).data())

    private suspend fun destination(passkey: PasskeyAssertion? = null): Resource<WithdrawalDestination> =
        ramp.floatWithdrawals
            .addDestination(crynce, TESTNET, DestinationCommand(address, "Ledger", pin, passkey))
            .let { if (it is Resource.Success) Resource.success(it.data.destination) else it as Resource.Error }

    private suspend fun usable(): WithdrawalDestination {
        val saved = destination().data()
        ramp.clock.advance(24 * 3_600)
        return saved
    }

    private suspend fun withdraw(
        amountMinor: Long,
        to: WithdrawalDestination,
        passkey: PasskeyAssertion? = null,
        idempotencyKey: String = "k-$amountMinor",
    ): Resource<Withdrawal> =
        ramp.floatWithdrawals.withdraw(
            crynce,
            TESTNET,
            WithdrawCommand(amountMinor, to.destinationId, pin, idempotencyKey, passkey),
        )

    private fun alerts(kind: AlertKind) = ramp.alertRows.of(kind)

    // ---- approved addresses only ----

    @Test
    fun `a withdrawal names a saved address by id, and there is no way to send to any other address`() =
        runTest {
            val fields =
                WithdrawCommand::class.java.declaredFields
                    .map { it.name }
                    .toSet()
            assertTrue("address" !in fields && "destinationAddress" !in fields, "the request has no address field")
            val unknown = java.util.UUID.randomUUID()
            withPin()
            val result =
                ramp.floatWithdrawals.withdraw(crynce, TESTNET, WithdrawCommand(5_000_000, unknown, pin, "x"))
            assertEquals(RampError.DestinationNotFound, result.error())
        }

    @Test
    fun `adding and removing an address are emailed, and removing it cancels a withdrawal not yet sent`() =
        runTest {
            withPin()
            val to = usable()
            val added = alerts(AlertKind.ADDRESS_ADDED).single()
            assertEquals(address, added.facts.address)
            assertNotNull(added.facts.usableFrom)
            assertNotNull(added.linkExpiresAt, "a security alert carries a lock link")
            val pending = withdraw(5_000_000, to).data()
            assertEquals(95_000_000, ramp.views.floatOf(ramp.walletOf(ramp.crynce))!!.freeMinor)

            ramp.floatWithdrawals.removeDestination(crynce, TESTNET, to.destinationId, pin).data()

            val cancelled = ramp.withdrawalRows.rows.getValue(pending.withdrawalId)
            assertEquals(WithdrawalState.CANCELLED to "address_removed", cancelled.state to cancelled.failureReason)
            assertEquals(100_000_000, ramp.views.floatOf(ramp.walletOf(ramp.crynce))!!.freeMinor, "freed")
            assertEquals(1, alerts(AlertKind.ADDRESS_REMOVED).size)
            assertEquals(0, ramp.release(), "nothing left to send")
            assertTrue(ramp.treasury.sends.isEmpty())
        }

    // ---- passkeys ----

    @Test
    fun `adding a passkey needs a fresh email code and the PIN, and is emailed`() =
        runTest {
            withPin()
            val wrongCode = ramp.merchantPasskeys.startRegistration(crynce, "Mac", "000000", pin)
            assertEquals(RampError.CodeInvalid, wrongCode.error())
            val code = code(SecurityCodePurpose.ADD_PASSKEY)
            assertEquals(
                RampError.PinInvalid,
                ramp.merchantPasskeys.startRegistration(crynce, "Mac", code, "111111").error(),
            )
            // The code went with the attempt that failed on the PIN only if it matched: ask a new one.
            val removeCode = code(SecurityCodePurpose.REMOVE_PASSKEY)
            assertEquals(
                RampError.CodeInvalid,
                ramp.merchantPasskeys.startRegistration(crynce, "Mac", removeCode, pin).error(),
                "a code is for one purpose only",
            )

            val passkey = register()
            assertEquals("MacBook", passkey.label)
            assertTrue(passkey.credentialId.contentEquals(key.credentialId))
            assertEquals(1, alerts(AlertKind.PASSKEY_ADDED).size)
            assertEquals(
                1,
                ramp.merchantPasskeys
                    .book(crynce)
                    .passkeys.size,
            )
        }

    @Test
    fun `a registration answer works once, and only for its own origin and with user verification`() =
        runTest {
            withPin()
            val options =
                ramp.merchantPasskeys
                    .startRegistration(
                        crynce,
                        "Phone",
                        code(SecurityCodePurpose.ADD_PASSKEY),
                        pin,
                    ).data()
            val phishing = TestAuthenticator(origin = "https://merchants-circaso.com")
            assertEquals(
                RampError.PasskeyInvalid,
                ramp.merchantPasskeys
                    .finishRegistration(
                        crynce,
                        options.registrationId,
                        phishing.create(options),
                    ).error(),
            )
            assertEquals(
                RampError.PasskeyInvalid,
                ramp.merchantPasskeys.finishRegistration(crynce, options.registrationId, key.create(options)).error(),
                "the registration was used up by the first answer",
            )

            val again =
                ramp.merchantPasskeys
                    .startRegistration(
                        crynce,
                        "Phone",
                        code(SecurityCodePurpose.ADD_PASSKEY),
                        pin,
                    ).data()
            assertEquals(
                RampError.PasskeyInvalid,
                ramp.merchantPasskeys
                    .finishRegistration(crynce, again.registrationId, key.create(again, userVerified = false))
                    .error(),
                "no fingerprint, face or device PIN: refused",
            )
            assertTrue(ramp.passkeyRows.rows.isEmpty())
        }

    @Test
    fun `signing in with a passkey gives a session and emails a sign-in alert`() =
        runTest {
            withPin()
            register()
            val challenge = ramp.stepUp.signInChallenge()
            assertTrue(challenge.options.allowCredentials.isEmpty(), "discoverable: no email asked")
            val signIn =
                FinishPasskeySignInFor(ramp).invoke(
                    PasskeyAssertion(challenge.challengeId, key.get(challenge.options.challenge)),
                )
            assertEquals(
                crynce,
                signIn
                    .data()
                    .merchant.merchant.merchantId,
            )
            val alert = alerts(AlertKind.NEW_SIGN_IN).single()
            assertEquals("passkey (MacBook)" to "Chrome on macOS", alert.facts.method to alert.facts.device)

            val replay =
                FinishPasskeySignInFor(
                    ramp,
                ).invoke(PasskeyAssertion(challenge.challengeId, key.get(challenge.options.challenge)))
            assertEquals(RampError.PasskeySignInFailed, replay.error(), "a challenge answers once")
            val stranger = TestAuthenticator()
            val other = ramp.stepUp.signInChallenge()
            assertEquals(
                RampError.PasskeySignInFailed,
                FinishPasskeySignInFor(ramp)
                    .invoke(PasskeyAssertion(other.challengeId, stranger.get(other.options.challenge)))
                    .error(),
                "an unknown passkey signs nobody in",
            )
        }

    @Test
    fun `an email-code sign-in is emailed too`() =
        runTest {
            ramp.requestCode(ramp.crynce.email, "203.0.113.7")
            val code =
                (
                    ramp.email.sent
                        .last()
                        .second as Email.MerchantSignInCode
                ).code
            ramp.verifyCode(
                ramp.crynce.email,
                code,
                TESTNET,
                SignInContext("Safari on iOS", "Nairobi, Kenya (approximate)"),
            )
            val alert = alerts(AlertKind.NEW_SIGN_IN).single()
            assertEquals("email code" to "Safari on iOS", alert.facts.method to alert.facts.device)
        }

    // ---- the step-up ----

    @Test
    fun `once a merchant has a passkey a withdrawal needs it as well as the PIN`() =
        runTest {
            withPin()
            val to = usable()
            register()

            assertEquals(RampError.StepUpRequired, withdraw(5_000_000, to).error())
            val proof = prove(StepUpBinding.withdraw(crynce, TESTNET, to.destinationId, 5_000_000))
            val taken = withdraw(5_000_000, to, proof).data()
            assertEquals(WithdrawalState.REQUESTED, taken.state)
            assertNotNull(
                ramp.passkeyRows.rows.values
                    .single()
                    .lastUsedAt,
            )
            assertEquals(1, alerts(AlertKind.WITHDRAWAL_REQUESTED).size)
        }

    @Test
    fun `a confirmation is bound to the exact request, so another amount, address or action is refused`() =
        runTest {
            withPin()
            val to = usable()
            register()

            val forFive = prove(StepUpBinding.withdraw(crynce, TESTNET, to.destinationId, 5_000_000))
            assertEquals(RampError.StepUpInvalid, withdraw(50_000_000, to, forFive).error(), "another amount")

            val forRemoval = prove(StepUpBinding.removeAddress(crynce, TESTNET, to.destinationId))
            assertEquals(RampError.StepUpInvalid, withdraw(5_000_000, to, forRemoval).error(), "another action")

            val forPin = prove(StepUpBinding.changePin(crynce))
            val added =
                ramp.floatWithdrawals.addDestination(
                    crynce,
                    TESTNET,
                    DestinationCommand("0xab5801a7d398351b8be11c439e05c5b3259aec9b", "Other", pin, forPin),
                )
            assertEquals(RampError.StepUpInvalid, added.error())
        }

    @Test
    fun `a confirmation works once and only for five minutes`() =
        runTest {
            withPin()
            val to = usable()
            register()
            val binding = StepUpBinding.withdraw(crynce, TESTNET, to.destinationId, 5_000_000)

            val proof = prove(binding)
            assertIs<Resource.Success<Withdrawal>>(withdraw(5_000_000, to, proof, "first"))
            assertEquals(RampError.StepUpInvalid, withdraw(5_000_000, to, proof, "replayed").error(), "replay")

            val late = prove(binding)
            ramp.clock.advance(6 * 60)
            assertEquals(RampError.StepUpInvalid, withdraw(5_000_000, to, late, "late").error(), "expired")
        }

    @Test
    fun `another merchant's challenge or passkey confirms nothing`() =
        runTest {
            withPin()
            val to = usable()
            register()
            val mallory = ramp.merchant("Mallory", floatMinor = 1)
            val theirs = TestAuthenticator()
            val binding = StepUpBinding.withdraw(mallory.merchantId, TESTNET, to.destinationId, 5_000_000)
            ramp.passkeyRows.rows.clear()
            ramp.passkeyRows.insert(passkeyOf(mallory.merchantId, theirs))
            val malloryChallenge = ramp.stepUp.challenge(binding).data()
            ramp.passkeyRows.rows.clear()
            register()

            val proof = PasskeyAssertion(malloryChallenge.challengeId, key.get(malloryChallenge.options.challenge))
            assertEquals(RampError.StepUpInvalid, withdraw(5_000_000, to, proof).error())
        }

    @Test
    fun `PIN, payment account and payout account changes need the passkey too, and are emailed`() =
        runTest {
            withPin()
            register()
            assertEquals(RampError.StepUpRequired, ramp.changePin(crynce, pin, "771204").error())
            assertIs<Resource.Success<Unit>>(
                ramp.changePin(crynce, pin, "771204", prove(StepUpBinding.changePin(crynce))),
            )
            assertEquals(1, alerts(AlertKind.PIN_CHANGED).size)

            val account = CollectionCredentials("Crynce-user2", "secret", "912")
            assertEquals(
                RampError.StepUpRequired,
                ramp.storePayments(crynce, TESTNET, PaymentAccountCommand(account, "771204")).error(),
            )
            val proof = prove(StepUpBinding.paymentAccount(crynce, "Crynce-user2", "912", null))
            assertIs<Resource.Success<*>>(
                ramp.storePayments(crynce, TESTNET, PaymentAccountCommand(account, "771204", null, proof)),
            )

            assertIs<Resource.Success<*>>(
                ramp.setPayoutAccount(crynce, TESTNET, "4242"),
                "the first time: nothing more",
            )
            assertEquals(RampError.PinInvalid, ramp.setPayoutAccount(crynce, TESTNET, "5353", "000000").error())
            assertEquals(RampError.StepUpRequired, ramp.setPayoutAccount(crynce, TESTNET, "5353", "771204").error())
            val payout = prove(StepUpBinding.payoutAccount(crynce, "5353"))
            assertIs<Resource.Success<*>>(ramp.setPayoutAccount(crynce, TESTNET, "5353", "771204", payout))
            assertEquals(2, alerts(AlertKind.PAYMENT_ACCOUNT_CHANGED).size)
        }

    @Test
    fun `without a passkey the PIN alone still works, unless staff require one`() =
        runTest {
            withPin()
            val to = usable()
            assertIs<Resource.Success<Withdrawal>>(withdraw(5_000_000, to))

            ramp.securitySettings.row = ramp.securitySettings.row.copy(merchantPasskeyRequired = true)
            assertEquals(RampError.PasskeyRequired, withdraw(6_000_000, to).error())
            assertEquals(RampError.PasskeyRequired, destination().error())
            assertEquals(RampError.PasskeyRequired, ramp.changePin(crynce, pin, "771204").error())

            register()
            val proof = prove(StepUpBinding.withdraw(crynce, TESTNET, to.destinationId, 6_000_000))
            assertIs<Resource.Success<Withdrawal>>(withdraw(6_000_000, to, proof))
        }

    @Test
    fun `removing a passkey takes another passkey or a code and the PIN, and is emailed`() =
        runTest {
            withPin()
            val first = register(key, "Mac")
            val phone = TestAuthenticator()
            val second = register(phone, "Phone")

            val selfProof = prove(StepUpBinding.removePasskey(crynce, first.passkeyId), key)
            assertEquals(
                RampError.StepUpInvalid,
                ramp.merchantPasskeys.remove(crynce, first.passkeyId, RemovalProof.Passkey(selfProof)).error(),
                "a passkey can't vouch for its own removal",
            )
            val other = prove(StepUpBinding.removePasskey(crynce, first.passkeyId), phone)
            ramp.merchantPasskeys.remove(crynce, first.passkeyId, RemovalProof.Passkey(other)).data()

            val wrong = RemovalProof.CodeAndPin("123456", pin)
            assertEquals(RampError.CodeInvalid, ramp.merchantPasskeys.remove(crynce, second.passkeyId, wrong).error())
            val proof = RemovalProof.CodeAndPin(code(SecurityCodePurpose.REMOVE_PASSKEY), pin)
            ramp.merchantPasskeys.remove(crynce, second.passkeyId, proof).data()
            assertTrue(ramp.passkeyRows.rows.isEmpty())
            assertEquals(2, alerts(AlertKind.PASSKEY_REMOVED).size)
        }

    // ---- alerts ----

    @Test
    fun `alerts are sent once, by the job, with the lock link on security ones only`() =
        runTest {
            withPin()
            val to = usable()
            withdraw(5_000_000, to)
            ramp.release()
            ramp.treasury.settleAll()
            ramp.release()
            val sent = ramp.alertSender.run()
            assertTrue(sent >= 3)
            assertEquals(0, ramp.alertSender.run(), "nothing twice")

            val emails = ramp.email.sent.map { it.second }
            val requested = emails.filterIsInstance<Email.MerchantWithdrawalRequested>().single()
            assertTrue(requested.lockUrl!!.startsWith("https://"), "a link to the portal's lock page")
            assertTrue("/lock#t=" in requested.lockUrl!!)
            val completed = emails.filterIsInstance<Email.MerchantWithdrawalCompleted>().single()
            assertTrue(completed.txUrl.startsWith("https://sepolia.basescan.org/tx/"))
            assertEquals("5 USDC", completed.amount)
            assertTrue(
                ramp.alertRows.rows.values
                    .all { it.state == AlertState.SENT },
            )
        }

    @Test
    fun `activity emails follow the choice and security ones can't be turned off`() =
        runTest {
            withPin()
            ramp.securityRows.setChoices(crynce, orderEmails = false, dailySummary = false, ramp.clock.instant())
            ramp.completed()
            destination()
            ramp.alertSender.run()

            assertEquals(AlertState.SKIPPED, alerts(AlertKind.BUY_COMPLETED).single().state)
            assertEquals(AlertState.SENT, alerts(AlertKind.ADDRESS_ADDED).single().state)
        }

    @Test
    fun `a completed buy is emailed with what came in, what went out and what was earned`() =
        runTest {
            ramp.completed()
            val facts = alerts(AlertKind.BUY_COMPLETED).single().facts
            assertEquals("1,300 KES" to "9.8 USDC", facts.received to facts.paid)
            assertNotNull(facts.earned)
        }

    @Test
    fun `a template with no id isn't sent, and a provider out of reach is tried again`() =
        runTest {
            withPin()
            destination()
            val noIds =
                object : EmailTemplateIds {
                    override suspend fun idOf(template: EmailTemplate): String? = null
                }
            val sender =
                AlertSender(
                    ramp.alertRows,
                    ramp.merchants,
                    ramp.securityRows,
                    ramp.email,
                    noIds,
                    ramp.lockLinks,
                    ramp.book,
                    ramp.clock,
                )
            sender.run()
            assertEquals("no_template", alerts(AlertKind.ADDRESS_ADDED).single().failure)

            ramp.merchantAlerts.pinChanged(crynce, ramp.clock.instant())
            ramp.email.failure = IntegrationError.Unavailable("Resend")
            ramp.alertSender.run()
            val waiting = alerts(AlertKind.PIN_CHANGED).single()
            assertEquals(AlertState.PENDING to 1, waiting.state to waiting.attempts)
            assertEquals(ramp.clock.instant().plus(AlertSender.backoff(1)), waiting.nextAttemptAt)
            ramp.email.failure = null
            ramp.clock.advance(60)
            ramp.alertSender.run()
            assertEquals(AlertState.SENT, alerts(AlertKind.PIN_CHANGED).single().state)
        }

    @Test
    fun `the same event is one alert however often its code runs`() =
        runTest {
            val at = ramp.clock.instant()
            val w = sampleWithdrawal()
            assertTrue(ramp.merchantAlerts.withdrawal(AlertKind.WITHDRAWAL_FAILED, w, at))
            assertTrue(!ramp.merchantAlerts.withdrawal(AlertKind.WITHDRAWAL_FAILED, w, at.plusSeconds(5)))
            assertEquals(1, alerts(AlertKind.WITHDRAWAL_FAILED).size)
        }

    // ---- the lock ----

    private suspend fun lockToken(): String {
        withPin()
        destination()
        val alert = alerts(AlertKind.ADDRESS_ADDED).last()
        return ramp.lockLinks.token(alert.alertId)!!
    }

    @Test
    fun `this wasn't me cancels withdrawals not yet sent, freezes cooling addresses, and takes the merchant offline`() =
        runTest {
            withPin()
            val to = usable()
            val claimed = withdraw(10_000_000, to, idempotencyKey = "claimed").data()
            ramp.withdrawalRows.claim(claimed.withdrawalId, ramp.clock.instant()) // the ops process got to it first
            val waiting = withdraw(20_000_000, to, idempotencyKey = "waiting").data()
            val cooling =
                ramp.floatWithdrawals
                    .addDestination(
                        crynce,
                        TESTNET,
                        DestinationCommand("0xab5801a7d398351b8be11c439e05c5b3259aec9b", "New", pin),
                    ).data()
                    .destination
            val session = ramp.verifyCodeFor(ramp.crynce.email).token
            val token = ramp.lockLinks.token(alerts(AlertKind.ADDRESS_ADDED).last().alertId)!!

            val outcome = ramp.withdrawalLock.lock(token).data()

            assertEquals(1, outcome.cancelled)
            assertEquals(
                WithdrawalState.CANCELLED,
                ramp.withdrawalRows.rows
                    .getValue(waiting.withdrawalId)
                    .state,
            )
            assertEquals(
                WithdrawalState.REQUESTED,
                ramp.withdrawalRows.rows
                    .getValue(claimed.withdrawalId)
                    .state,
            )
            assertEquals(
                90_000_000,
                ramp.views.floatOf(ramp.walletOf(ramp.crynce))!!.freeMinor,
                "only the claimed one holds",
            )
            assertNotNull(
                ramp.destinationRows.rows
                    .getValue(cooling.destinationId)
                    .frozenAt,
            )
            assertNull(
                ramp.destinationRows.rows
                    .getValue(to.destinationId)
                    .frozenAt,
                "a usable address isn't frozen",
            )
            assertEquals(
                OfflineReason.LOCKED,
                ramp.merchants.rows
                    .getValue(crynce)
                    .offlineReason,
            )
            assertEquals(RampError.SessionInvalid, ramp.resolveSession(session).error(), "signed out everywhere")
            assertTrue(ramp.alerts.stuck.any { "cancelled=1" in it }, "the people on call are alerted")
            assertEquals(1, alerts(AlertKind.WITHDRAWALS_LOCKED).size)

            assertEquals(RampError.WithdrawalsLocked, withdraw(1_000_000, to, idempotencyKey = "after").error())
            assertEquals(RampError.WithdrawalsLocked, destination().error())
            ramp.release()
            assertEquals(1, ramp.treasury.sends.size, "the claimed one still goes; the cancelled one never does")
            assertEquals(
                claimed.withdrawalId.toString(),
                ramp.treasury.sends
                    .single()
                    .refId,
            )
        }

    @Test
    fun `a lock link works once`() =
        runTest {
            val token = lockToken()
            assertEquals(
                false,
                ramp.withdrawalLock
                    .lock(token)
                    .data()
                    .alreadyLocked,
            )
            val again = ramp.withdrawalLock.lock(token).data()
            assertEquals(true to 0, again.alreadyLocked to again.cancelled)
            assertEquals(1, alerts(AlertKind.WITHDRAWALS_LOCKED).size)
        }

    @Test
    fun `a lock link expires, and a forged or edited one does nothing`() =
        runTest {
            val token = lockToken()
            val edited = token.dropLast(2) + if (token.endsWith("AA")) "BB" else "AA"
            assertEquals(RampError.LockLinkInvalid, ramp.withdrawalLock.lock(edited).error())
            assertEquals(RampError.LockLinkInvalid, ramp.withdrawalLock.lock("not-a-token").error())
            val activity = ramp.lockLinks.token(java.util.UUID.randomUUID())!!
            assertEquals(RampError.LockLinkInvalid, ramp.withdrawalLock.lock(activity).error(), "no such alert")

            ramp.clock.advance(MerchantAlerts.LINK_LIFETIME.seconds + 1)
            assertEquals(RampError.LockLinkExpired, ramp.withdrawalLock.lock(token).error())
            assertTrue(!ramp.securityRows.find(crynce).locked)
        }

    @Test
    fun `only staff lift a lock, and frozen addresses stay frozen`() =
        runTest {
            val token = lockToken()
            val cooling =
                ramp.destinationRows.rows.values
                    .single()
            ramp.withdrawalLock.lock(token).data()
            assertEquals(
                RampError.NotLocked,
                ramp.withdrawalLock.unlock(ramp.merchant("Other", 1).merchantId, "s", "r").error(),
            )

            ramp.withdrawalLock.unlock(crynce, "staff-1", "spoke to the owner").data()
            val state = ramp.securityRows.find(crynce)
            assertEquals(false to "staff-1", state.locked to state.unlockedBy)
            ramp.clock.advance(48 * 3_600)
            val frozen = ramp.destinationRows.rows.getValue(cooling.destinationId)
            assertTrue(!frozen.isUsableAt(ramp.clock.instant()), "remove it and add it again")
        }

    @Test
    fun `a withdrawal cancelled before the ops process claimed it is never sent`() =
        runTest {
            withPin()
            val to = usable()
            val w = withdraw(5_000_000, to).data()
            ramp.withdrawalRows.cancelUnclaimed(crynce, null, "locked", ramp.clock.instant())
            assertTrue(!ramp.withdrawalRows.claim(w.withdrawalId, ramp.clock.instant()))
            ramp.release()
            assertTrue(ramp.treasury.sends.isEmpty())
        }

    @Test
    fun `sent, completed and failed withdrawals are each emailed once`() =
        runTest {
            withPin()
            val to = usable()
            withdraw(5_000_000, to)
            ramp.release()
            val transactionId =
                ramp.treasury.transactions.keys
                    .single()
            ramp.treasury.settle(transactionId, TreasuryTransactionState.FAILED)
            ramp.release()
            ramp.release()
            assertEquals(1, alerts(AlertKind.WITHDRAWAL_FAILED).size)
            assertEquals(0, alerts(AlertKind.WITHDRAWAL_COMPLETED).size)
            assertTrue(WithdrawalSends.withdrawalKey(java.util.UUID.randomUUID()).version() == 3)
        }

    private fun sampleWithdrawal() =
        Withdrawal(
            withdrawalId = java.util.UUID.randomUUID(),
            merchantId = crynce,
            network = TESTNET,
            blockchain = CHAIN,
            destinationId = java.util.UUID.randomUUID(),
            destinationAddress = address,
            destinationLabel = "Ledger",
            amountMinor = 1_000_000,
            state = WithdrawalState.FAILED,
            idempotencyKey = null,
            failureReason = "refused",
            createdAt = ramp.clock.instant(),
            updatedAt = ramp.clock.instant(),
        )

    private fun passkeyOf(merchantId: com.circaso.ramp.api.MerchantId, authenticator: TestAuthenticator) =
        Passkey(
            java.util.UUID.randomUUID(),
            merchantId,
            authenticator.credentialId,
            ByteArray(0),
            0,
            "theirs",
            emptyList(),
            false,
            false,
            ramp.clock.instant(),
            null,
        )
}

/** MA5 over the harness. */
private class FinishPasskeySignInFor(private val ramp: Harness) {
    suspend fun invoke(assertion: PasskeyAssertion) =
        com.circaso.ramp.domain.usecases
            .FinishPasskeySignIn(
                ramp.stepUp,
                ramp.merchants,
                ramp.portalSessions,
                ramp.tokens,
                ramp.views,
                ramp.merchantAlerts,
                ramp.clock,
            ).invoke(
                assertion,
                TESTNET,
                SignInContext("Chrome on macOS", "Kenya (approximate)"),
            )
}
