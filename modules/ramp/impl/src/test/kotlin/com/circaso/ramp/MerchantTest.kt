package com.circaso.ramp

import com.circaso.integrations.api.CollectionCredentials
import com.circaso.integrations.api.Email
import com.circaso.integrations.api.IntegrationError
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.MerchantState
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.PageCursor
import com.circaso.ramp.domain.models.PaymentAccountCommand
import com.circaso.ramp.domain.models.ProfileCommand
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.RampState
import com.circaso.ramp.domain.models.SignedIn
import com.circaso.ramp.domain.support.Callbacks
import com.circaso.shared.Network
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.getOrNull
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The merchant portal: sign-in by email and code, the account's set-up, the PIN and the trading session. */
class MerchantTest {
    private val ramp = Harness()
    private val address = "203.0.113.7"
    private val pin = "482913"
    private val payhero = CollectionCredentials("api-user", "api-pass", "911")

    private fun <T> Resource<T>.error() = (this as Resource.Error).error

    private suspend fun signIn(email: String = "pay@crynce.co.ke"): SignedIn = ramp.verifyCodeFor(email)

    private fun idOf(signedIn: SignedIn): MerchantId = signedIn.merchant.merchant.merchantId

    /** A merchant who signed in, gave a profile, was approved, got a funded wallet and connected PayHero. */
    private suspend fun ready(email: String = "pay@crynce.co.ke", floatMinor: Long = 100_000_000): MerchantId {
        val id = idOf(signIn(email))
        ramp.applyAsMerchant(id, TESTNET, ProfileCommand("Crynce Ltd.", PhoneNumber.parse("+254712000111")!!))
        ramp.decideMerchant(id, approve = true, by = "staff")
        ramp.release()
        ramp.treasury.balances[ramp.merchantWallets.find(id, TESTNET)!!.custodyWalletId] = floatMinor
        assertIs<Resource.Success<*>>(ramp.storePayments(id, TESTNET, PaymentAccountCommand(payhero, pin)))
        return id
    }

    // Sign-in

    @Test
    fun `a code is emailed, signs in once, and the first sign-in makes the merchant account`() =
        runTest {
            val sent = ramp.requestCode("pay@crynce.co.ke", address).getOrNull()!!
            assertEquals(60, sent.retryAfterSeconds)
            val (to, email) = ramp.email.sent.single()
            assertEquals("pay@crynce.co.ke", to)
            val code = (email as Email.MerchantSignInCode).code
            assertTrue(code.length == 6 && code.all(Char::isDigit))
            assertEquals(10, email.minutes)
            assertFalse(code in email.toString(), "the code never prints")

            val signedIn = ramp.verifyCode("pay@crynce.co.ke", code, TESTNET).getOrNull()!!

            assertEquals(MerchantState.NEW, signedIn.merchant.merchant.state)
            assertEquals(listOf("profile", "approval", "wallet", "payments"), signedIn.merchant.missing)
            assertEquals(ramp.clock.instant().plus(Duration.ofHours(12)), signedIn.expiresAt)
            assertEquals(idOf(signedIn), ramp.resolveSession(signedIn.token).getOrNull())
            assertFalse(signedIn.token in signedIn.toString())
            assertEquals(RampError.CodeInvalid, ramp.verifyCode("pay@crynce.co.ke", code, TESTNET).error(), "used up")

            ramp.clock.advance(61)
            val again = signIn()
            assertEquals(idOf(signedIn), idOf(again), "the same account, a second session")
            assertNotEquals(signedIn.token, again.token)
        }

    @Test
    fun `only a digest of the session token is kept, and a wrong or expired token is no session`() =
        runTest {
            val signedIn = signIn()

            assertTrue(
                ramp.portalSessions.rows.keys
                    .none { signedIn.token in it },
            )
            assertEquals(
                64,
                ramp.portalSessions.rows.keys
                    .single()
                    .length,
                "a SHA-256",
            )
            assertEquals(RampError.SessionInvalid, ramp.resolveSession(null).error())
            assertEquals(RampError.SessionInvalid, ramp.resolveSession(" ").error())
            assertEquals(RampError.SessionInvalid, ramp.resolveSession(signedIn.token + "x").error())

            ramp.clock.advance(11 * 3_600 + 60)
            assertIs<Resource.Success<*>>(ramp.resolveSession(signedIn.token))
            assertEquals(
                ramp.clock.instant().plus(Duration.ofHours(12)),
                ramp.portalSessions.rows.values
                    .single()
                    .expiresAt,
                "used in its last hour, it is renewed",
            )

            ramp.clock.advance(12 * 3_600 + 1)
            assertEquals(RampError.SessionInvalid, ramp.resolveSession(signedIn.token).error())
        }

    @Test
    fun `a code allows five tries and lasts ten minutes`() =
        runTest {
            ramp.requestCode("pay@crynce.co.ke", address)
            val code = ramp.email.lastCode()
            val wrong = if (code == "000000") "111111" else "000000"

            repeat(
                5,
            ) { assertEquals(RampError.CodeInvalid, ramp.verifyCode("pay@crynce.co.ke", wrong, TESTNET).error()) }
            val locked = ramp.verifyCode("pay@crynce.co.ke", code, TESTNET).error()
            assertIs<RampError.CodeLocked>(locked)
            assertNotNull(locked.details?.retryAfterSeconds)
            assertEquals(RampError.CodeInvalid, ramp.verifyCode("pay@crynce.co.ke", code, TESTNET).error(), "dead")

            ramp.clock.advance(61)
            ramp.requestCode("pay@crynce.co.ke", address)
            ramp.clock.advance(601)
            assertEquals(
                RampError.CodeInvalid,
                ramp.verifyCode("pay@crynce.co.ke", ramp.email.lastCode(), TESTNET).error(),
            )
            assertTrue(
                ramp.merchants.rows.values
                    .none { it.email == "pay@crynce.co.ke" },
                "no account was made",
            )
        }

    @Test
    fun `asking for codes is limited per email and per address, and says how long to wait`() =
        runTest {
            ramp.requestCode("a@example.com", address)
            val soon = ramp.requestCode("a@example.com", address).error()
            assertIs<RampError.CodeLocked>(soon)
            assertEquals(60, soon.details?.retryAfterSeconds)

            ramp.clock.advance(61)
            ramp.requestCode("a@example.com", address)
            ramp.clock.advance(61)
            ramp.requestCode("a@example.com", address)
            ramp.clock.advance(61)
            assertIs<RampError.CodeLocked>(ramp.requestCode("a@example.com", address).error(), "three in ten minutes")

            val other = Harness()
            repeat(10) { assertIs<Resource.Success<*>>(other.requestCode("m$it@example.com", address)) }
            assertIs<RampError.CodeLocked>(other.requestCode("m10@example.com", address).error(), "ten an hour")
            assertIs<Resource.Success<*>>(other.requestCode("m11@example.com", "198.51.100.9"))
        }

    @Test
    fun `a code that can't be emailed is an upstream error, not a failure of the server`() =
        runTest {
            ramp.email.failure = IntegrationError.Rejected("No Resend template is configured.")

            assertEquals(RampError.EmailUnavailable, ramp.requestCode("pay@crynce.co.ke", address).error())
        }

    @Test
    fun `signing out ends the session and takes the merchant offline`() =
        runTest {
            val signedIn = signIn()
            val id = ready("other@example.com")
            ramp.goOnline(id, TESTNET, pin, 60)
            val session = signIn("other@example.com".also { ramp.clock.advance(61) })

            ramp.signOut(session.token)

            assertEquals(RampError.SessionInvalid, ramp.resolveSession(session.token).error())
            assertNull(ramp.merchants.find(id)!!.onlineUntil)
            assertNull(ramp.sessions.of(id))
            assertIs<Resource.Success<*>>(ramp.resolveSession(signedIn.token), "another merchant's session stays")
            assertIs<Resource.Success<*>>(ramp.signOut("no-such-token"))
        }

    // Setting up

    @Test
    fun `the profile applies once from NEW, and later only updates the name and phone`() =
        runTest {
            val id = idOf(signIn())
            val profile = ProfileCommand("Crynce", PhoneNumber.parse("+254712000111")!!)

            val applied = ramp.applyAsMerchant(id, TESTNET, profile).getOrNull()!!
            assertEquals(MerchantState.APPLIED, applied.merchant.state)
            assertEquals(listOf("approval", "wallet", "payments"), applied.missing)

            ramp.decideMerchant(id, approve = true, by = "staff")
            val renamed = ProfileCommand("Crynce Ltd.", PhoneNumber.parse("+254712000222")!!)
            val after = ramp.applyAsMerchant(id, TESTNET, renamed).getOrNull()!!
            assertEquals(MerchantState.APPROVED, after.merchant.state)
            assertEquals("Crynce Ltd." to "+254712000222", after.merchant.displayName to after.merchant.contactPhone)
        }

    @Test
    fun `the overview shows the wallet to fund, the float less what is spoken for, and what is still missing`() =
        runTest {
            val id = ready(floatMinor = 50_000_000)

            val overview = ramp.getMerchant(id, TESTNET).getOrNull()!!
            assertEquals(CHAIN, overview.wallet?.blockchain)
            assertEquals(50_000_000L to 0L, overview.float!!.balanceMinor to overview.float!!.reservedMinor)
            assertEquals("payhero" to "911", overview.payments!!.provider to overview.payments!!.channelId)
            assertTrue(overview.payments!!.callbackUrl!!.startsWith("https://api.test/v1/hooks/payhero/crynce-ltd-"))
            assertTrue(overview.pinSet && overview.canGoOnline && !overview.online)
            assertEquals(emptyList(), overview.missing)

            // a float too small for the smallest order (10 shillings buys about 0.075 USDC) is "missing"
            ramp.treasury.balances[overview.wallet!!.custodyWalletId] = 50_000
            assertEquals(listOf("float"), ramp.getMerchant(id, TESTNET).getOrNull()!!.missing)
            assertEquals(RampError.MerchantSetupIncomplete, ramp.goOnline(id, TESTNET, pin, 60).error())

            // the custodian can't be asked: the float is unknown, which isn't the same as missing
            ramp.treasury.failure = IntegrationError.Unavailable("Circle")
            val unknown = ramp.getMerchant(id, TESTNET).getOrNull()!!
            assertNull(unknown.float)
            assertEquals(emptyList(), unknown.missing)
            ramp.treasury.failure = null

            // on mainnet the same account has no wallet yet, and mainnet always asks for a (live) identity check
            assertEquals(listOf("kyc", "wallet"), ramp.getMerchant(id, Network.MAINNET).getOrNull()!!.missing)
            assertEquals(RampError.MerchantNotFound, ramp.getMerchant(MerchantId.new(), TESTNET).error())
        }

    @Test
    fun `payment credentials are checked with the provider, sealed, and answered with the merchant's own address`() =
        runTest {
            val id = idOf(signIn())
            val command = PaymentAccountCommand(payhero, pin)

            assertEquals(RampError.MerchantSetupIncomplete, ramp.storePayments(id, TESTNET, command).error(), "NEW")
            ramp.applyAsMerchant(id, TESTNET, ProfileCommand("Crynce Ltd.", PhoneNumber.parse("+254712000111")!!))

            ramp.collections.rejectCredentials = true
            assertEquals(RampError.CredentialsRejected, ramp.storePayments(id, TESTNET, command).error())
            ramp.collections.rejectCredentials = false
            ramp.collections.failure = IntegrationError.Unavailable("PayHero")
            assertEquals(RampError.CollectionsUnavailable, ramp.storePayments(id, TESTNET, command).error())
            ramp.collections.failure = null
            assertNull(ramp.credentials.rows[id], "nothing is kept that the provider didn't accept")

            val saved = ramp.storePayments(id, TESTNET, command).getOrNull()!!

            assertEquals(
                "api-user",
                ramp.collections.verified
                    .single()
                    .username,
            )
            val key = ramp.merchants.find(id)!!.callbackKey!!
            assertTrue(Regex("crynce-ltd-[0-9A-Za-z]{16}").matches(key), key)
            assertEquals("https://api.test/v1/hooks/payhero/$key", saved.callbackUrl)
            val sealed = ramp.credentials.rows.getValue(id)
            val stored =
                listOf(sealed.sealed, sealed.wrappedKey, sealed.pinSalt).joinToString("") {
                    String(it, Charsets.ISO_8859_1)
                }
            listOf(
                "api-user",
                "api-pass",
                pin,
            ).forEach { assertFalse(it in stored + sealed.kdfParams, "plaintext $it") }
            assertFalse("api-pass" in command.toString() + sealed.toString() + payhero.toString())

            // a suspended merchant can't change them; an approved one can, with the PIN already set
            ramp.decideMerchant(id, approve = false, by = "staff")
            assertEquals(RampError.MerchantNotApproved, ramp.storePayments(id, TESTNET, command).error())
            ramp.decideMerchant(id, approve = true, by = "staff")
            val replaced = PaymentAccountCommand(CollectionCredentials("new-user", "new-pass", "912"), "000000")
            assertEquals(
                RampError.PinInvalid,
                ramp.storePayments(id, TESTNET, replaced).error(),
                "it never resets the PIN",
            )
            val again = ramp.storePayments(id, TESTNET, PaymentAccountCommand(replaced.credentials, pin)).getOrNull()!!
            assertEquals(saved.callbackUrl, again.callbackUrl, "the address stays")
            assertEquals(
                "912",
                ramp.credentials.rows
                    .getValue(id)
                    .channelId,
            )
        }

    @Test
    fun `after staff switch the network's provider, a merchant reconnects under it before trading again`() =
        runTest {
            val id = ready()
            ramp.merchants.rows.remove(ramp.crynce.merchantId)
            ramp.goOnline(id, TESTNET, pin, 240)
            assertEquals(
                PAYHERO,
                ramp.credentials.rows
                    .getValue(id)
                    .provider,
            )

            val current = ramp.getSettings(TESTNET).getOrNull()!!
            val switched = current.copy(collectionsProvider = DARAJA)
            assertIs<Resource.Success<*>>(ramp.updateSettings(switched, current.version, "staff-1"))

            // the account they stored is PayHero's: it shows, but it no longer counts as connected
            val stale = ramp.getMerchant(id, TESTNET).getOrNull()!!
            assertEquals(listOf("payments"), stale.missing)
            assertEquals(PAYHERO, stale.payments?.provider)
            assertFalse(stale.paymentsCurrent || stale.canGoOnline)
            assertTrue(stale.pinSet, "the PIN is still theirs")
            assertEquals(RampError.NoMerchantAvailable, ramp.buy().error(), "not a candidate, though still online")
            ramp.goOffline(id)
            assertEquals(RampError.MerchantSetupIncomplete, ramp.goOnline(id, TESTNET, pin, 60).error())

            // RM5 checks the new account with the new provider and seals it as that provider's, under the same PIN
            val account = CollectionCredentials("daraja-key", "daraja-secret", "174379")
            assertEquals(
                RampError.PinInvalid,
                ramp.storePayments(id, TESTNET, PaymentAccountCommand(account, "000000")).error(),
            )
            assertIs<Resource.Success<*>>(ramp.storePayments(id, TESTNET, PaymentAccountCommand(account, pin)))
            assertEquals(
                "daraja-key",
                ramp.daraja.verified
                    .single()
                    .username,
            )
            assertEquals(1, ramp.collections.verified.size, "PayHero was asked only the first time")
            assertEquals(
                DARAJA to "174379",
                ramp.credentials.rows
                    .getValue(id)
                    .let { it.provider to it.channelId },
            )
            assertEquals(emptyList(), ramp.getMerchant(id, TESTNET).getOrNull()!!.missing)

            assertIs<Resource.Success<*>>(ramp.goOnline(id, TESTNET, pin, 60))
            val order = ramp.prompted()
            assertEquals(DARAJA, order.collectionsProvider)
            assertEquals(
                "daraja-key",
                ramp.daraja.promptedWith
                    .single()
                    .username,
            )
        }

    @Test
    fun `payment credentials can't be stored while the network's provider isn't one this server offers`() =
        runTest {
            val id = idOf(signIn())
            ramp.applyAsMerchant(id, TESTNET, ProfileCommand("Crynce", PhoneNumber.parse("+254712000111")!!))
            ramp.settingsRows.set(TESTNET) { it.copy(collectionsProvider = "gone") }
            ramp.book.invalidate()

            val refused = ramp.storePayments(id, TESTNET, PaymentAccountCommand(payhero, pin))

            assertEquals(RampError.CollectionsUnavailable, refused.error())
            assertNull(ramp.credentials.rows[id])
        }

    @Test
    fun `a new callback address replaces the old one at once`() =
        runTest {
            val id = ready()
            val old = ramp.merchants.find(id)!!.callbackKey!!
            ramp.goOnline(id, TESTNET, pin, 240)
            ramp.merchants.rows.remove(ramp.crynce.merchantId)
            val order = ramp.prompted()
            ramp.collections.pay(order)

            val rotated = ramp.rotateCallback(id, TESTNET).getOrNull()!!
            val new = ramp.merchants.find(id)!!.callbackKey!!

            assertNotEquals(old, new)
            assertEquals(Callbacks.url("https://api.test", new), rotated.callbackUrl)
            ramp.paymentCallback(old, order.orderId.toString())
            assertEquals(OrderState.PAYMENT_REQUESTED, ramp.now(order).state, "the old address rings nothing")
            ramp.paymentCallback(new, order.orderId.toString())
            assertEquals(OrderState.PAID, ramp.now(order).state)

            val bare = idOf(signIn("bare@example.com"))
            assertEquals(RampError.MerchantSetupIncomplete, ramp.rotateCallback(bare, TESTNET).error())
        }

    @Test
    fun `a callback slug is readable and never empty`() {
        assertEquals("crynce-ltd", Callbacks.slug("Crynce Ltd."))
        assertEquals("merchant", Callbacks.slug("@@@"))
        assertEquals("a-b", Callbacks.slug("  A -- b  "))
        assertTrue(Callbacks.slug("x".repeat(80)).length <= 24)
        assertEquals("https://api.test/v1/hooks/payhero/k", Callbacks.url("https://api.test/", "k"))
    }

    // The PIN and the trading session

    @Test
    fun `going online needs approval, a wallet, payments and the PIN, and unlocks the keys on this node only`() =
        runTest {
            val bare = idOf(signIn("bare@example.com"))
            assertEquals(RampError.MerchantNotApproved, ramp.goOnline(bare, TESTNET, pin, 60).error())
            ramp.applyAsMerchant(bare, TESTNET, ProfileCommand("Bare", PhoneNumber.parse("+254712000333")!!))
            ramp.decideMerchant(bare, approve = true, by = "staff")
            assertEquals(
                RampError.MerchantSetupIncomplete,
                ramp.goOnline(bare, TESTNET, pin, 60).error(),
                "no payments",
            )
            ramp.clock.advance(61)

            val id = ready()
            assertEquals(
                RampError.MerchantSetupIncomplete,
                ramp.goOnline(id, Network.MAINNET, pin, 60).error(),
                "no wallet",
            )
            assertEquals(RampError.PinInvalid, ramp.goOnline(id, TESTNET, "000000", 60).error())
            assertNull(ramp.sessions.of(id))

            val online = ramp.goOnline(id, TESTNET, pin, 240).getOrNull()!!

            assertEquals(ramp.clock.instant().plus(Duration.ofMinutes(240)), online.until)
            assertEquals(online.until, ramp.merchants.find(id)!!.onlineUntil)
            val keys = ramp.sessions.of(id)!!
            assertEquals(
                Triple("api-user", "api-pass", "911"),
                keys.credentials.let { Triple(it.username, it.password, it.collectionChannelId) },
            )
            assertEquals(
                online.until,
                ramp.sessions.marks[id],
                "Redis holds only that the merchant is online, and until when",
            )
            assertTrue(ramp.getMerchant(id, TESTNET).getOrNull()!!.online)

            // the orders it then takes are prompted with these keys
            ramp.merchants.rows.remove(ramp.crynce.merchantId)
            ramp.prompted()
            assertEquals(
                "api-user",
                ramp.collections.promptedWith
                    .single()
                    .username,
            )
        }

    @Test
    fun `five wrong PINs lock PIN entry for thirty minutes, whatever is tried next`() =
        runTest {
            val id = ready()

            repeat(4) { assertEquals(RampError.PinInvalid, ramp.goOnline(id, TESTNET, "000000", 60).error()) }
            val fifth = ramp.goOnline(id, TESTNET, "000000", 60).error()
            assertIs<RampError.PinLocked>(fifth)
            assertEquals(1_800, fifth.details?.retryAfterSeconds)

            assertIs<RampError.PinLocked>(ramp.goOnline(id, TESTNET, pin, 60).error(), "even the right PIN waits")
            assertIs<RampError.PinLocked>(ramp.changePin(id, pin, "771204").error())
            val command = PaymentAccountCommand(payhero, pin)
            assertIs<RampError.PinLocked>(ramp.storePayments(id, TESTNET, command).error())
            assertNull(ramp.sessions.of(id))

            ramp.clock.advance(1_801)
            assertIs<Resource.Success<*>>(ramp.goOnline(id, TESTNET, pin, 60))
            repeat(4) { ramp.goOnline(id, TESTNET, "000000", 60) }
            assertIs<Resource.Success<*>>(ramp.goOnline(id, TESTNET, pin, 60), "a right PIN cleared the count")
        }

    @Test
    fun `changing the PIN needs the old one, and the old one then opens nothing`() =
        runTest {
            val id = ready()
            val sealedBefore = ramp.credentials.rows.getValue(id)

            assertEquals(RampError.PinInvalid, ramp.changePin(id, "000000", "771204").error())
            assertIs<Resource.Success<*>>(ramp.changePin(id, pin, "771204"))

            val sealedAfter = ramp.credentials.rows.getValue(id)
            assertTrue(sealedBefore.sealed.contentEquals(sealedAfter.sealed), "only the data key is re-wrapped")
            assertFalse(sealedBefore.wrappedKey.contentEquals(sealedAfter.wrappedKey))
            assertEquals(RampError.PinInvalid, ramp.goOnline(id, TESTNET, pin, 60).error())
            assertEquals(
                "api-pass",
                ramp.goOnline(id, TESTNET, "771204", 60).let {
                    ramp.sessions
                        .of(id)!!
                        .credentials.password
                },
            )

            val bare = idOf(signIn("bare@example.com"))
            assertEquals(RampError.MerchantSetupIncomplete, ramp.changePin(bare, pin, "771204").error())
        }

    @Test
    fun `without the master key nothing can be stored or unlocked, and the server still answers`() =
        runTest {
            val id = ready()
            ramp.masterKey = null

            assertEquals(RampError.VaultUnavailable, ramp.goOnline(id, TESTNET, pin, 60).error())
            assertEquals(RampError.VaultUnavailable, ramp.changePin(id, pin, "771204").error())
            ramp.masterKey = "not-hex"
            assertEquals(RampError.VaultUnavailable, ramp.goOnline(id, TESTNET, pin, 60).error())

            // another master key is not this merchant's: it opens nothing, and reads as a wrong PIN
            ramp.masterKey = "ff".repeat(32)
            assertEquals(RampError.PinInvalid, ramp.goOnline(id, TESTNET, pin, 60).error())
            ramp.masterKey = MASTER_KEY
            assertIs<Resource.Success<*>>(ramp.goOnline(id, TESTNET, pin, 60))
        }

    @Test
    fun `a node that restarts holds nobody's keys - its merchants are offline until they unlock again`() =
        runTest {
            val id = ready()
            ramp.merchants.rows.remove(ramp.crynce.merchantId)
            ramp.goOnline(id, TESTNET, pin, 240)
            val order = ramp.prompted()
            ramp.collections.pay(order)

            ramp.restartLosingCopies()

            assertFalse(ramp.getMerchant(id, TESTNET).getOrNull()!!.online)
            assertEquals(
                RampError.NoMerchantAvailable,
                ramp.buy(ramp.user("+254700000002"), PhoneNumber.parse("+254700000002")!!).error(),
            )
            ramp.callback(order)
            ramp.clock.advance(900)
            ramp.sweep(TESTNET)
            assertEquals(
                OrderState.PAYMENT_REQUESTED,
                ramp.now(order).state,
                "it waits: never expired or paid on a guess",
            )

            ramp.goOnline(id, TESTNET, pin, 60)
            ramp.callback(order)
            assertEquals(OrderState.PAID, ramp.now(order).state, "the next unlock lets the status query run")
        }

    @Test
    fun `going offline stops new orders, and the keys stay only until the open ones finish`() =
        runTest {
            val id = ready()
            ramp.merchants.rows.remove(ramp.crynce.merchantId)
            ramp.goOnline(id, TESTNET, pin, 240)
            val order = ramp.prompted()

            val offline = ramp.goOffline(id).getOrNull()!!

            assertFalse(offline.online)
            assertNull(ramp.merchants.find(id)!!.onlineUntil)
            assertNull(ramp.sessions.marks[id])
            assertNotNull(ramp.sessions.of(id), "an order still waits for its payment")
            val bob = ramp.user("+254700000002")
            assertEquals(RampError.NoMerchantAvailable, ramp.buy(bob, PhoneNumber.parse("+254700000002")!!).error())
            ramp.endSessions()
            assertNotNull(ramp.sessions.of(id))

            ramp.collections.pay(order)
            ramp.callback(order)
            assertEquals(OrderState.PAID, ramp.now(order).state, "the open order finished")
            ramp.endSessions()
            assertNull(ramp.sessions.of(id), "and then the keys are dropped")
        }

    @Test
    fun `a trading window that ends takes the merchant offline`() =
        runTest {
            val id = ready()
            ramp.goOnline(id, TESTNET, pin, 60)

            ramp.clock.advance(3_601)
            ramp.endSessions()

            assertNull(ramp.merchants.find(id)!!.onlineUntil)
            assertNull(ramp.sessions.of(id))
            assertNotNull(ramp.sessions.of(ramp.crynce.merchantId), "a merchant still in their window keeps theirs")
        }

    // Their orders

    @Test
    fun `a merchant sees their own orders on a network, a page at a time`() =
        runTest {
            val users = listOf("+254700000002", "+254700000003", "+254700000004")
            val orders =
                users.map {
                    val order = ramp.prompted(ramp.user(it), PhoneNumber.parse(it)!!)
                    ramp.clock.advance(5)
                    order
                }
            val id = ramp.crynce.merchantId

            val page = ramp.merchantOrders(id, TESTNET, 2, null).getOrNull()!!
            assertEquals(listOf(orders[2].orderId, orders[1].orderId), page.map { it.orderId })
            val cursor = PageCursor(page.last().createdAt, page.last().orderId.value)
            assertEquals(
                listOf(orders[0].orderId),
                ramp.merchantOrders(id, TESTNET, 2, cursor).getOrNull()!!.map { it.orderId },
            )
            assertTrue(ramp.merchantOrders(id, Network.MAINNET, 10, null).getOrNull()!!.isEmpty())
            assertTrue(ramp.merchantOrders(MerchantId.new(), TESTNET, 10, null).getOrNull()!!.isEmpty())
        }

    // Staff

    @Test
    fun `staff approve a merchant who applied, and a suspension takes them offline at once`() =
        runTest {
            val id = idOf(signIn())
            assertEquals(RampError.MerchantNotReady, ramp.decideMerchant(id, approve = true, by = "staff-1").error())
            assertEquals(RampError.MerchantNotFound, ramp.decideMerchant(MerchantId.new(), true, "staff-1").error())

            ramp.applyAsMerchant(id, TESTNET, ProfileCommand("Crynce", PhoneNumber.parse("+254712000111")!!))
            val approved = ramp.decideMerchant(id, approve = true, by = "staff-1").getOrNull()!!
            assertEquals(MerchantState.APPROVED to "staff-1", approved.merchant.state to approved.merchant.approvedBy)
            assertFalse(approved.paymentsConnected)

            val online = ramp.crynce.merchantId
            val suspended = ramp.decideMerchant(online, approve = false, by = "staff-2").getOrNull()!!
            assertEquals(MerchantState.SUSPENDED, suspended.merchant.state)
            assertNull(suspended.merchant.onlineUntil)
            assertNull(ramp.sessions.marks[online])
            assertEquals(RampError.NoMerchantAvailable, ramp.buy().error())

            val listed = ramp.listMerchants(null, 10).getOrNull()!!
            assertEquals(setOf(id, online), listed.map { it.merchant.merchantId }.toSet())
            assertEquals(1, ramp.listMerchants(null, 1).getOrNull()!!.size)
        }

    @Test
    fun `settings are seeded once, cached for ten seconds, and a change is version-checked and validated`() =
        runTest {
            val fresh = Harness()
            fresh.settingsRows.rows.clear()
            val seeded = fresh.getSettings(Network.MAINNET).getOrNull()!!
            assertEquals(RampState.OFF, seeded.state)
            assertTrue(seeded.kycRequired)
            assertEquals(
                RampSettings
                    .defaults(
                        Network.TESTNET,
                        seeded.updatedAt,
                    ).copy(updatedAt = fresh.book.of(TESTNET).updatedAt),
                fresh.book.of(TESTNET),
            )
            assertFalse(fresh.book.of(TESTNET).kycRequired, "the testnet may run without KYC until Didit is built")

            val current = ramp.getSettings(TESTNET).getOrNull()!!
            val wanted = current.copy(state = RampState.PREVIEW, feeBps = 150)
            val change = ramp.updateSettings(wanted, current.version, "staff-1").getOrNull()!!
            assertEquals(current.version + 1, change.after.version)
            assertEquals("staff-1", change.after.updatedBy)
            assertEquals(150, ramp.book.of(TESTNET).feeBps, "this process sees its own change at once")
            assertEquals(RampError.SettingsChanged, ramp.updateSettings(wanted, current.version, "staff-2").error())

            suspend fun refused(field: String, change: (RampSettings) -> RampSettings) {
                val error = ramp.updateSettings(change(wanted), null, "staff-1").error()
                assertIs<RampError.SettingsInvalid>(error, field)
                assertEquals(
                    field,
                    error.details
                        ?.fields
                        ?.single()
                        ?.field,
                )
            }
            refused("feeBps") { it.copy(feeBps = 501) }
            refused("merchantShareBps") { it.copy(merchantShareBps = 10_001) }
            refused("chains") { it.copy(chains = listOf("BASE")) }
            refused("chains") { it.copy(chains = emptyList()) }
            refused("orderMaxMinor") { it.copy(orderMaxMinor = it.orderMinMinor - 100) }
            refused("orderMinMinor") { it.copy(orderMinMinor = 150) }
            refused("feeSettleHourUtc") { it.copy(feeSettleHourUtc = 24) }
            refused("collectionsProvider") { it.copy(collectionsProvider = "log") }
            refused("collectionsProvider") { it.copy(collectionsProvider = "fake") }
            refused("callbackBaseUrl") { it.copy(callbackBaseUrl = "api.circaso.com") }
            refused("callbackBaseUrl") { it.copy(callbackBaseUrl = "https://api.circaso.com/") }
            refused("kycRequired") { it.copy(network = Network.MAINNET, chains = listOf("BASE"), kycRequired = false) }

            // another process changed the row: this one keeps what it read for ten seconds, then sees it
            ramp.settingsRows.set(TESTNET) { it.copy(feeBps = 100) }
            assertEquals(150, ramp.book.of(TESTNET).feeBps)
            ramp.clock.advance(11)
            assertEquals(100, ramp.book.of(TESTNET).feeBps)

            // the table can't be read: what was read last stays in force
            ramp.settingsRows.failure = IllegalStateException("down")
            ramp.clock.advance(11)
            assertEquals(100, ramp.book.of(TESTNET).feeBps)
            assertTrue(ramp.alerts.stuck.isNotEmpty())
        }

    @Test
    fun `the preview list and the review queue are staff's`() =
        runTest {
            val bob: UserId = ramp.user("+254700000002")
            assertTrue(ramp.setPreview(bob, TESTNET, true, "staff-1").getOrNull()!!)
            assertFalse(ramp.setPreview(bob, TESTNET, true, "staff-1").getOrNull()!!, "already there")
            assertTrue(ramp.preview.has(bob, TESTNET))
            assertFalse(ramp.preview.has(bob, Network.MAINNET))
            assertTrue(ramp.setPreview(bob, TESTNET, false, "staff-1").getOrNull()!!)
            assertFalse(ramp.setPreview(bob, TESTNET, false, "staff-1").getOrNull()!!)

            val order = ramp.prompted()
            ramp.flow.review(order, "payment_mismatch")
            val queue = ramp.reviewOrders(null, null, 10).getOrNull()!!
            assertEquals(order.orderId to "Crynce", queue.single().order.orderId to queue.single().merchantName)
            assertTrue(ramp.reviewOrders(Network.MAINNET, null, 10).getOrNull()!!.isEmpty())
            assertTrue(ramp.reviewOrders(TESTNET, order.orderId, 10).getOrNull()!!.isEmpty())
        }
}
