package com.circaso.chama

import com.circaso.chama.api.ContributionRecorded
import com.circaso.chama.api.RequestId
import com.circaso.chama.api.RequestPaid
import com.circaso.chama.domain.errors.ChamaError
import com.circaso.chama.domain.models.Approval
import com.circaso.chama.domain.models.AskPayoutCommand
import com.circaso.chama.domain.models.Contribution
import com.circaso.chama.domain.models.Decision
import com.circaso.chama.domain.models.Goal
import com.circaso.chama.domain.models.Payout
import com.circaso.chama.domain.models.RequestState
import com.circaso.chama.domain.models.SetGoalCommand
import com.circaso.chama.domain.ports.ChamaFrame
import com.circaso.chama.domain.ports.ChamaOutbox
import com.circaso.chama.domain.ports.FrameSender
import com.circaso.chama.domain.repositories.ContributionRepository
import com.circaso.chama.domain.repositories.GoalRepository
import com.circaso.chama.domain.repositories.RequestRepository
import com.circaso.chama.domain.support.Members
import com.circaso.chama.domain.support.RequestFrames
import com.circaso.chama.domain.usecases.AskPayout
import com.circaso.chama.domain.usecases.AttachPayment
import com.circaso.chama.domain.usecases.DecidePayout
import com.circaso.chama.domain.usecases.ExpireRequests
import com.circaso.chama.domain.usecases.GetChama
import com.circaso.chama.domain.usecases.ListLedger
import com.circaso.chama.domain.usecases.RecordTransfer
import com.circaso.chama.domain.usecases.SetGoal
import com.circaso.messaging.api.ChatDirectory
import com.circaso.messaging.api.ChatId
import com.circaso.shared.DomainEvent
import com.circaso.shared.EventBus
import com.circaso.shared.EventEnvelope
import com.circaso.shared.EventId
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.api.Profiles
import com.circaso.users.api.PublicProfile
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.api.TransferSummary
import com.circaso.wallet.api.WalletAmount
import com.circaso.wallet.api.Wallets
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemoryGoals : GoalRepository {
    val rows = mutableMapOf<ChatId, Goal>()

    override suspend fun find(chatId: ChatId) = rows[chatId]

    override suspend fun save(goal: Goal) {
        rows[goal.chatId] = goal
    }
}

private class MemoryContributions : ContributionRepository {
    val rows = mutableMapOf<TransferId, Contribution>()

    override suspend fun insert(contribution: Contribution) =
        rows.putIfAbsent(contribution.transferId, contribution) == null

    override suspend fun list(
        chatId: ChatId,
        limit: Int,
        before: Instant?,
    ) = rows.values
        .filter { it.chatId == chatId && (before == null || it.at < before) }
        .sortedByDescending { it.at }
        .take(limit)

    override suspend fun totals(chatId: ChatId) =
        rows.values
            .filter { it.chatId == chatId }
            .groupBy { it.userId }
            .mapValues { (_, c) -> c.sumOf { it.amountMinor } }
}

private class MemoryRequests : RequestRepository {
    val rows = mutableMapOf<RequestId, Payout>()

    override suspend fun insert(request: Payout) {
        rows[request.requestId] = request
    }

    override suspend fun find(requestId: RequestId) = rows[requestId]

    override suspend fun byTransfer(transferId: TransferId) = rows.values.firstOrNull { it.transferId == transferId }

    override suspend fun open(chatId: ChatId) = rows.values.filter { it.chatId == chatId && it.isOpen }

    override suspend fun paid(
        chatId: ChatId,
        limit: Int,
        before: Instant?,
    ) = rows.values.filter { it.chatId == chatId && it.state == RequestState.PAID }.take(limit)

    override suspend fun addApproval(requestId: RequestId, approval: Approval): Boolean {
        val r = rows[requestId] ?: return false
        if (r.approvals.any { it.userId == approval.userId }) return false
        rows[requestId] = r.copy(approvals = r.approvals + approval)
        return true
    }

    override suspend fun move(
        requestId: RequestId,
        to: RequestState,
        from: Set<RequestState>,
        transferId: TransferId?,
        at: Instant,
    ): Payout? {
        val r = rows[requestId] ?: return null
        if (r.state !in from) return null
        return r.copy(state = to, transferId = transferId ?: r.transferId, updatedAt = at).also { rows[requestId] = it }
    }

    override suspend fun expire(now: Instant): Int {
        val due = rows.values.filter { it.state == RequestState.PENDING && it.expiresAt < now }
        due.forEach { rows[it.requestId] = it.copy(state = RequestState.EXPIRED, updatedAt = now) }
        return due.size
    }

    override suspend fun paidOutTotal(chatId: ChatId) =
        rows.values
            .filter {
                it.chatId == chatId &&
                    it.state == RequestState.PAID
            }.sumOf { it.amountMinor }
}

private class FakeChats : ChatDirectory {
    val members = mutableMapOf<ChatId, Set<UserId>>()
    val admins = mutableMapOf<ChatId, Set<UserId>>()

    override suspend fun isMember(chatId: ChatId, userId: UserId) = userId in members[chatId].orEmpty()

    override suspend fun members(chatId: ChatId) = members[chatId].orEmpty()

    override suspend fun admins(chatId: ChatId) = admins[chatId].orEmpty()
}

private class FakeProfiles : Profiles {
    val names = mutableMapOf<UserId, String>()

    override suspend fun get(ids: Set<UserId>) =
        ids
            .mapNotNull { id ->
                names[id]?.let {
                    id to
                        PublicProfile(id, it, null, null, null)
                }
            }.toMap()
}

private class RecordingFrames : FrameSender {
    val sent = mutableListOf<Pair<Set<UserId>, ChamaFrame>>()

    override suspend fun send(userIds: Collection<UserId>, frame: ChamaFrame) {
        sent += userIds.toSet() to frame
    }

    inline fun <reified T : ChamaFrame> ofType() = sent.map { it.second }.filterIsInstance<T>()
}

private class FakeWallets : Wallets {
    val transfers = mutableMapOf<TransferId, TransferSummary>()

    override suspend fun hasWallet(userId: UserId) = true

    override suspend fun transfer(transferId: TransferId) = transfers[transferId]
}

private class MemoryBus : EventBus {
    val published = mutableListOf<EventEnvelope>()

    override fun <E : DomainEvent> envelope(event: E, eventSerializer: KSerializer<E>): EventEnvelope =
        EventEnvelope(
            EventId.new(),
            event.type,
            1,
            Instant.EPOCH,
            "chama",
            null,
            Json.encodeToJsonElement(eventSerializer, event) as JsonElement,
        )

    override suspend fun publish(envelope: EventEnvelope) {
        published += envelope
    }

    override fun subscribe(subscription: com.circaso.shared.EventSubscription) = Unit
}

private class RecordingOutbox : ChamaOutbox {
    val written = mutableListOf<EventEnvelope>()

    override suspend fun write(events: List<EventEnvelope>) {
        written += events
    }
}

private class Harness {
    val goals = MemoryGoals()
    val contributions = MemoryContributions()
    val requests = MemoryRequests()
    val chats = FakeChats()
    val profiles = FakeProfiles()
    val frames = RecordingFrames()
    val wallets = FakeWallets()
    val bus = MemoryBus()
    val outbox = RecordingOutbox()
    var now: Instant = Instant.parse("2026-10-29T10:00:00Z")
    val clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?) = this

            override fun instant() = now
        }
    val members = Members(chats, profiles)
    val requestFrames = RequestFrames(goals, members, frames)
    val setGoal = SetGoal(members, goals, contributions, frames, clock)
    val getChama = GetChama(members, goals, contributions, requests)
    val listLedger = ListLedger(members, goals, contributions, requests)
    val createRequest = AskPayout(members, goals, requests, requestFrames, clock)
    val decideRequest = DecidePayout(members, requests, requestFrames, clock)
    val attachPayment = AttachPayment(members, goals, requests, wallets, requestFrames, clock)
    val recordTransfer =
        RecordTransfer(members, goals, contributions, requests, frames, requestFrames, outbox, bus, clock)
    val expireRequests = ExpireRequests(requests, clock)

    val chat = ChatId.new()
    val otieno = UserId.new()
    val wanjiru = UserId.new()
    val achieng = UserId.new()
    val juma = UserId.new()

    init {
        chats.members[chat] = setOf(otieno, wanjiru, achieng, juma)
        chats.admins[chat] = setOf(otieno, wanjiru)
        profiles.names[otieno] = "Otieno"
        profiles.names[wanjiru] = "Wanjiru"
        profiles.names[achieng] = "Achieng"
        profiles.names[juma] = "Juma"
    }

    suspend fun goal(treasurer: UserId = otieno) =
        setGoal(SetGoalCommand(otieno, chat, "Holiday Fund", 50_000_000_000, null, treasurer))
}

class ChamaTest {
    @Test
    fun `only an admin sets the goal, and the treasurer must be a member`() =
        runTest {
            val h = Harness()
            assertIs<Resource.Error>(h.setGoal(SetGoalCommand(h.juma, h.chat, "Holiday Fund", 10, null, null)))
                .also { assertEquals(ChamaError.NotAdmin, it.error) }
            assertIs<Resource.Error>(
                h.setGoal(SetGoalCommand(h.otieno, h.chat, "Holiday Fund", 10, null, UserId.new())),
            ).also { assertEquals(ChamaError.TreasurerNotMember, it.error) }
            assertIs<Resource.Error>(h.setGoal(SetGoalCommand(h.otieno, h.chat, " ", 10, null, null)))
            val goal = assertIs<Resource.Success<Goal>>(h.goal()).data
            assertEquals(h.otieno, goal.treasurerId)
            assertEquals(1, goal.version)
            assertEquals(1, h.frames.ofType<ChamaFrame.GoalUpdated>().size)
            val again =
                assertIs<Resource.Success<Goal>>(
                    h.setGoal(
                        SetGoalCommand(
                            h.wanjiru,
                            h.chat,
                            "Holiday Fund 2027",
                            60_000_000_000,
                            null,
                            h.wanjiru,
                        ),
                    ),
                ).data
            assertEquals(2, again.version)
            assertEquals(h.wanjiru, again.treasurerId)
        }

    @Test
    fun `a member's confirmed payment to the treasurer from the chat is a contribution, once`() =
        runTest {
            val h = Harness()
            h.goal()
            val t = TransferId.new()
            h.recordTransfer(t, h.wanjiru, h.otieno, 1_000_000_000, h.chat)
            h.recordTransfer(t, h.wanjiru, h.otieno, 1_000_000_000, h.chat) // the event arrives twice
            val view =
                assertIs<Resource.Success<com.circaso.chama.domain.models.ChamaView>>(h.getChama(h.juma, h.chat)).data
            assertEquals(1_000_000_000, view.contributedMinor)
            assertEquals(1_000_000_000, view.heldMinor)
            assertEquals(mapOf(h.wanjiru to 1_000_000_000L), view.contributorsMinor)
            assertEquals(4, view.memberCount)
            val frame = h.frames.ofType<ChamaFrame.ContributionAdded>().single()
            assertEquals("Wanjiru", frame.displayName)
            assertEquals("1000", frame.amount.amount)
            assertEquals(1, h.bus.published.count { it.type == ContributionRecorded.TYPE })
            assertEquals(h.bus.published.size, h.outbox.written.size, "every event has its outbox row")

            // not a contribution: to someone else, from the treasurer, from another chat, or from a stranger
            h.recordTransfer(TransferId.new(), h.wanjiru, h.achieng, 5, h.chat)
            h.recordTransfer(TransferId.new(), h.otieno, h.wanjiru, 5, h.chat)
            h.recordTransfer(TransferId.new(), h.wanjiru, h.otieno, 5, ChatId.new())
            h.recordTransfer(TransferId.new(), UserId.new(), h.otieno, 5, h.chat)
            assertEquals(1, h.contributions.rows.size)
            assertIs<Resource.Error>(h.getChama(UserId.new(), h.chat))
                .also { assertEquals(ChamaError.NotAMember, it.error) }
            assertEquals(1, assertIs<Resource.Success<List<*>>>(h.listLedger(h.juma, h.chat, 10, null)).data.size)
        }

    @Test
    fun `a request needs two admins, an admin asking counts as one, and one decline closes it`() =
        runTest {
            val h = Harness()
            h.goal()
            val byMember =
                assertIs<Resource.Success<Payout>>(
                    h.createRequest(
                        AskPayoutCommand(
                            h.juma,
                            h.chat,
                            null,
                            "0xbus",
                            "Coast Bus",
                            12_000_000_000,
                            "bus deposit",
                        ),
                    ),
                ).data
            assertEquals(RequestState.PENDING, byMember.state)
            assertEquals(2, byMember.approvalsNeeded)
            assertEquals(0, byMember.approvals.size)
            assertEquals(
                setOf(h.otieno, h.wanjiru),
                h.frames
                    .ofType<ChamaFrame.RequestUpdated>()
                    .last()
                    .waitingOn
                    .toSet(),
            )

            assertIs<Resource.Error>(h.decideRequest(h.juma, byMember.requestId, Decision.APPROVE))
                .also { assertEquals(ChamaError.NotAdmin, it.error) }
            val one =
                assertIs<Resource.Success<Payout>>(h.decideRequest(h.otieno, byMember.requestId, Decision.APPROVE)).data
            assertEquals(RequestState.PENDING, one.state)
            assertEquals(
                listOf(h.wanjiru),
                h.frames
                    .ofType<ChamaFrame.RequestUpdated>()
                    .last()
                    .waitingOn,
            )
            assertIs<Resource.Error>(h.decideRequest(h.otieno, byMember.requestId, Decision.APPROVE))
                .also { assertEquals(ChamaError.AlreadyDecided, it.error) }
            val two =
                assertIs<Resource.Success<Payout>>(
                    h.decideRequest(h.wanjiru, byMember.requestId, Decision.APPROVE),
                ).data
            assertEquals(RequestState.APPROVED, two.state)
            assertIs<Resource.Error>(h.decideRequest(h.wanjiru, byMember.requestId, Decision.DECLINE))
                .also { assertEquals(ChamaError.RequestClosed, it.error) }

            val byAdmin =
                assertIs<Resource.Success<Payout>>(
                    h.createRequest(
                        AskPayoutCommand(
                            h.otieno,
                            h.chat,
                            h.achieng,
                            null,
                            null,
                            500_000_000,
                            null,
                        ),
                    ),
                ).data
            assertEquals(1, byAdmin.approvals.size)
            assertEquals(RequestState.PENDING, byAdmin.state)
            val declined =
                assertIs<Resource.Success<Payout>>(h.decideRequest(h.wanjiru, byAdmin.requestId, Decision.DECLINE)).data
            assertEquals(RequestState.DECLINED, declined.state)

            assertIs<Resource.Error>(
                h.createRequest(AskPayoutCommand(h.juma, h.chat, h.achieng, "0xboth", null, 5, null)),
            ).also { assertEquals(ChamaError.PayeeInvalid, it.error) }
            assertIs<Resource.Error>(h.createRequest(AskPayoutCommand(h.juma, h.chat, h.achieng, null, null, 0, null)))
                .also { assertEquals(ChamaError.AmountInvalid, it.error) }
        }

    @Test
    fun `the treasurer links the matching transfer, and the wallet's confirmation pays the request`() =
        runTest {
            val h = Harness()
            h.goal()
            val request =
                assertIs<Resource.Success<Payout>>(
                    h.createRequest(
                        AskPayoutCommand(
                            h.otieno,
                            h.chat,
                            h.achieng,
                            null,
                            null,
                            500_000_000,
                            "fare",
                        ),
                    ),
                ).data
            h.decideRequest(h.wanjiru, request.requestId, Decision.APPROVE)
            val t = TransferId.new()
            h.wallets.transfers[t] =
                TransferSummary(
                    t,
                    h.otieno,
                    h.achieng,
                    "0xachieng",
                    WalletAmount("USDC", "500000000", "500"),
                    "SUBMITTED",
                    h.chat,
                )

            assertIs<Resource.Error>(h.attachPayment(h.wanjiru, request.requestId, t))
                .also { assertEquals(ChamaError.NotTreasurer, it.error) }
            val wrong = TransferId.new()
            h.wallets.transfers[wrong] =
                TransferSummary(
                    wrong,
                    h.otieno,
                    h.achieng,
                    "0xachieng",
                    WalletAmount("USDC", "400000000", "400"),
                    "SUBMITTED",
                    h.chat,
                )
            assertIs<Resource.Error>(h.attachPayment(h.otieno, request.requestId, wrong))
                .also { assertEquals(ChamaError.TransferMismatch, it.error) }

            val linked = assertIs<Resource.Success<Payout>>(h.attachPayment(h.otieno, request.requestId, t)).data
            assertEquals(RequestState.APPROVED, linked.state)
            assertEquals(t, linked.transferId)
            h.recordTransfer(t, h.otieno, h.achieng, 500_000_000, h.chat)
            val paid = h.requests.rows.getValue(request.requestId)
            assertEquals(RequestState.PAID, paid.state)
            assertEquals(1, h.bus.published.count { it.type == RequestPaid.TYPE })
            assertEquals(0, h.contributions.rows.size, "a payout is never a contribution")
            val view =
                assertIs<Resource.Success<com.circaso.chama.domain.models.ChamaView>>(h.getChama(h.otieno, h.chat)).data
            assertEquals(500_000_000, view.paidOutMinor)
            assertTrue(view.isTreasurer)
        }

    @Test
    fun `a request nobody decided within 48 hours expires`() =
        runTest {
            val h = Harness()
            h.goal()
            val request =
                assertIs<Resource.Success<Payout>>(
                    h.createRequest(
                        AskPayoutCommand(
                            h.juma,
                            h.chat,
                            h.achieng,
                            null,
                            null,
                            5,
                            null,
                        ),
                    ),
                ).data
            h.now = h.now.plus(Duration.ofHours(47))
            assertEquals(0, h.expireRequests())
            h.now = h.now.plus(Duration.ofHours(2))
            assertEquals(1, h.expireRequests())
            assertEquals(
                RequestState.EXPIRED,
                h.requests.rows
                    .getValue(request.requestId)
                    .state,
            )
            assertNull(h.requests.open(h.chat).firstOrNull())
        }
}
