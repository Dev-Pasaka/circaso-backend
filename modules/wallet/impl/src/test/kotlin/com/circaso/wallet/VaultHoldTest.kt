package com.circaso.wallet

import com.circaso.identity.api.PinVaultOpened
import com.circaso.shared.EventEnvelope
import com.circaso.shared.UserId
import com.circaso.wallet.domain.models.HoldReason
import com.circaso.wallet.domain.models.Payee
import com.circaso.wallet.domain.models.PaymentHold
import com.circaso.wallet.domain.models.WalletQuery
import com.circaso.wallet.domain.usecases.HoldAfterVaultOpened
import com.circaso.wallet.presentation.events.IdentityEventHandler
import com.circaso.wallet.presentation.services.IdentityEventsService
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `identity.PinVaultOpened` (2026-11-11) pauses the person's outgoing payments for 24 hours from the opening: W4 and
 * W17 answer `wallet.on_hold` and W1 shows the hold.
 */
class VaultHoldTest {
    private val h = Harness()
    private val amina = UserId.new()
    private val bob = UserId.new()
    private val handler = IdentityEventHandler(IdentityEventsService(HoldAfterVaultOpened(h.holds))).subscription()

    private suspend fun opened(userId: UserId, at: Instant) {
        val envelope = EventEnvelope.of(PinVaultOpened(userId), PinVaultOpened.serializer(), occurredAt = at)
        handler.handlers.getValue(envelope.type).handle(envelope)
    }

    private suspend fun payBob() = h.pay(amina, Payee.Person(bob), 10_000_000, "BASE-SEPOLIA", UUID.randomUUID(), null)

    @Test
    fun `the wallet group reads identity's stream for the opening`() {
        assertEquals("wallet", handler.group)
        assertEquals("events:identity", handler.stream)
    }

    @Test
    fun `an opening holds payments for 24 hours from when it happened, and W1 shows until when and why`() =
        runBlocking<Unit> {
            h.fund(amina, 100_000_000)
            h.walletOn(bob)
            h.reach.connect(amina, bob)
            val at = Instant.parse("2026-11-11T08:00:00Z")

            opened(amina, at)

            val hold = PaymentHold(at.plus(Duration.ofHours(24)), HoldReason.VAULT_OPENED)
            assertEquals(hold, h.holds.holds[amina])
            assertEquals("wallet.on_hold", payBob().code())
            assertEquals(hold, h.getWallet(WalletQuery(amina, null)).data().hold)
            assertEquals(null, h.holds.holds[bob], "only the opener's payments are held")
        }

    @Test
    fun `a repeated or late delivery never shortens a hold`() =
        runBlocking<Unit> {
            val first = Instant.parse("2026-11-11T08:00:00Z")
            val second = first.plus(Duration.ofHours(3))

            opened(amina, second)
            opened(amina, first)
            opened(amina, second)

            assertEquals(second.plus(Duration.ofHours(24)), h.holds.holds[amina]?.until)
        }

    @Test
    fun `a longer hold for another reason is kept`() =
        runBlocking<Unit> {
            val at = Instant.parse("2026-11-11T08:00:00Z")
            val restored = PaymentHold(at.plus(Duration.ofHours(48)), HoldReason.RELINKED)
            h.holds.place(amina, restored)

            opened(amina, at)

            assertEquals(restored, h.holds.holds[amina])
        }
}
