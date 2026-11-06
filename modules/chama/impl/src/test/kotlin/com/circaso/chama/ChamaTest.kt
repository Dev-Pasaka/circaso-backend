package com.circaso.chama

import com.circaso.chama.api.ContributionRecorded
import com.circaso.chama.api.RequestId
import com.circaso.chama.api.RequestPaid
import com.circaso.chama.domain.errors.ChamaError
import com.circaso.chama.domain.models.Approval
import com.circaso.chama.domain.models.ApprovalPolicy
import com.circaso.chama.domain.models.AskPayoutCommand
import com.circaso.chama.domain.models.Contribution
import com.circaso.chama.domain.models.Decision
import com.circaso.chama.domain.models.DirectPayout
import com.circaso.chama.domain.models.Goal
import com.circaso.chama.domain.models.GroupSettings
import com.circaso.chama.domain.models.Payout
import com.circaso.chama.domain.models.RequestState
import com.circaso.chama.domain.models.SetGoalCommand
import com.circaso.chama.domain.models.SetSettingsCommand
import com.circaso.chama.domain.models.WalletTransfer
import com.circaso.chama.domain.ports.ChamaFrame
import com.circaso.chama.domain.ports.ChamaOutbox
import com.circaso.chama.domain.ports.FrameSender
import com.circaso.chama.domain.repositories.ContributionRepository
import com.circaso.chama.domain.repositories.DirectPayoutRepository
import com.circaso.chama.domain.repositories.GoalRepository
import com.circaso.chama.domain.repositories.RequestRepository
import com.circaso.chama.domain.repositories.SettingsRepository
import com.circaso.chama.domain.support.ChamaNames
import com.circaso.chama.domain.support.ChamaViews
import com.circaso.chama.domain.support.Members
import com.circaso.chama.domain.support.RequestFrames
import com.circaso.chama.domain.usecases.AskPayout
import com.circaso.chama.domain.usecases.AttachPayment
import com.circaso.chama.domain.usecases.DecidePayout
import com.circaso.chama.domain.usecases.ExpireRequests
import com.circaso.chama.domain.usecases.FindApprovedPayout
import com.circaso.chama.domain.usecases.GetChama
import com.circaso.chama.domain.usecases.GetChamaDigest
import com.circaso.chama.domain.usecases.GetGroupMoney
import com.circaso.chama.domain.usecases.ListLedger
import com.circaso.chama.domain.usecases.ListMyChamas
import com.circaso.chama.domain.usecases.RecordTransfer
import com.circaso.chama.domain.usecases.RemoveGoal
import com.circaso.chama.domain.usecases.SetGoal
import com.circaso.chama.domain.usecases.SetSettings
import com.circaso.chama.presentation.mappers.toAgentResponse
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
import com.circaso.wallet.api.GroupWalletSummary
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.api.TransferSummary
import com.circaso.wallet.api.WalletAmount
import com.circaso.wallet.api.WalletId
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemoryGoals : GoalRepository {
    val rows = mutableMapOf<ChatId, Goal>()

    override suspend fun find(chatId: ChatId) = rows[chatId]

    override suspend fun save(goal: Goal) {
        rows[goal.chatId] = goal
    }

    override suspend fun delete(chatId: ChatId) = rows.remove(chatId) != null
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

    override suspend fun totals(chatId: ChatId, since: Instant?) =
        rows.values
            .filter { it.chatId == chatId && !it.legacy && (since == null || it.at >= since) }
            .groupBy { it.userId }
            .mapValues { (_, c) -> c.sumOf { it.amountMinor } }
}

private class MemoryDirectPayouts : DirectPayoutRepository {
    val rows = mutableMapOf<TransferId, DirectPayout>()

    override suspend fun insert(payout: DirectPayout) = rows.putIfAbsent(payout.transferId, payout) == null

    override suspend fun total(chatId: ChatId) = rows.values.filter { it.chatId == chatId }.sumOf { it.amountMinor }
}

private class MemorySettings : SettingsRepository {
    val rows = mutableMapOf<ChatId, GroupSettings>()

    override suspend fun find(chatId: ChatId) = rows[chatId] ?: GroupSettings.default(chatId)

    override suspend fun save(settings: GroupSettings) {
        rows[settings.chatId] = settings
    }
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
    val owners = mutableMapOf<ChatId, UserId>()

    override suspend fun isMember(chatId: ChatId, userId: UserId) = userId in members[chatId].orEmpty()

    override suspend fun members(chatId: ChatId) = members[chatId].orEmpty()

    override suspend fun admins(chatId: ChatId) = admins[chatId].orEmpty()

    override suspend fun isGroup(chatId: ChatId) = true

    override suspend fun groupsOf(userId: UserId) = members.filterValues { userId in it }.keys.toList()

    override suspend fun owner(chatId: ChatId) = owners[chatId]
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
    val groups = mutableMapOf<ChatId, GroupWalletSummary>()

    override suspend fun hasWallet(userId: UserId) = true

    override suspend fun transfer(transferId: TransferId) = transfers[transferId]

    override suspend fun groupWallet(chatId: ChatId) = groups[chatId]
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
    val settings = MemorySettings()
    val directPayouts = MemoryDirectPayouts()
    val chats = FakeChats()
    val profiles = FakeProfiles()
    val frames = RecordingFrames()
    val wallets = FakeWallets()
    val bus = MemoryBus()
    val outbox = RecordingOutbox()
    var now: Instant = Instant.parse("2026-11-03T10:00:00Z")
    val clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?) = this

            override fun instant() = now
        }
    val members = Members(chats, profiles)
    val requestFrames = RequestFrames(goals, members, frames, wallets)
    val setGoal = SetGoal(members, goals, contributions, frames, clock, wallets)
    val views = ChamaViews(members, goals, contributions, requests, wallets, settings, directPayouts)
    val getChama = GetChama(views)
    val getMoney = GetGroupMoney(views)
    val removeGoal = RemoveGoal(members, goals, frames)
    val setSettings = SetSettings(members, settings, frames, clock)
    val findApproved = FindApprovedPayout(requests)
    val listLedger = ListLedger(members, views)
    val listMyChamas = ListMyChamas(chats, views, ChamaNames(profiles))
    val getChamaDigest = GetChamaDigest(views, ChamaNames(profiles))
    val createRequest = AskPayout(members, requests, requestFrames, clock, wallets, settings)
    val decideRequest = DecidePayout(members, requests, requestFrames, clock)
    val attachPayment = AttachPayment(members, requests, wallets, requestFrames, clock)
    val recordTransfer =
        RecordTransfer(
            members,
            goals,
            contributions,
            requests,
            frames,
            requestFrames,
            outbox,
            bus,
            directPayouts,
            clock,
        )
    val expireRequests = ExpireRequests(requests, clock)

    val chat = ChatId.new()
    val otieno = UserId.new() // the group's owner, who set its wallet's PIN
    val wanjiru = UserId.new()
    val achieng = UserId.new()
    val juma = UserId.new()

    /** The group wallet's id: also the holder id its transfers name as sender or recipient. */
    val walletId = WalletId.new()
    val wallet = UserId(walletId.value)

    init {
        chats.members[chat] = setOf(otieno, wanjiru, achieng, juma)
        chats.admins[chat] = setOf(otieno, wanjiru)
        chats.owners[chat] = otieno
        profiles.names[otieno] = "Otieno"
        profiles.names[wanjiru] = "Wanjiru"
        profiles.names[achieng] = "Achieng"
        profiles.names[juma] = "Juma"
    }

    fun withWallet(status: String = "ACTIVE") {
        wallets.groups[chat] = GroupWalletSummary(walletId, chat, status, otieno)
    }

    suspend fun goal() = setGoal(SetGoalCommand(otieno, chat, "Holiday Fund", 50_000_000_000, null))

    /** A member's payment into the group wallet, as `wallet.TransferConfirmed` tells it. */
    suspend fun contribute(
        from: UserId,
        amountMinor: Long,
        transferId: TransferId = TransferId.new(),
        to: ChatId? = chat,
    ) = recordTransfer(WalletTransfer(transferId, from, amountMinor, chat, null, to, null))

    suspend fun ask(
        by: UserId,
        payee: UserId? = achieng,
        address: String? = null,
        amountMinor: Long = 500_000_000,
    ) = createRequest(AskPayoutCommand(by, chat, payee, address, address?.let { "Coast Bus" }, amountMinor, null))
}

class ChamaTest {
    @Test
    fun `a target needs the group's active wallet, and only an admin sets it`() =
        runTest {
            val h = Harness()
            assertEquals(ChamaError.NoWallet, assertIs<Resource.Error>(h.goal()).error)
            h.withWallet(status = "SETTING_UP")
            assertEquals(ChamaError.NoWallet, assertIs<Resource.Error>(h.goal()).error)
            h.withWallet()
            assertIs<Resource.Error>(h.setGoal(SetGoalCommand(h.juma, h.chat, "Holiday Fund", 10, null)))
                .also { assertEquals(ChamaError.NotAdmin, it.error) }
            assertIs<Resource.Error>(h.setGoal(SetGoalCommand(h.otieno, h.chat, " ", 10, null)))

            val goal = assertIs<Resource.Success<Goal>>(h.goal()).data
            assertNull(goal.treasurerId, "no treasurer: the money is in the group wallet")
            assertEquals(1, goal.version)
            assertEquals(1, h.frames.ofType<ChamaFrame.GoalUpdated>().size)
            h.now = h.now.plusSeconds(60)
            val again =
                assertIs<Resource.Success<Goal>>(
                    h.setGoal(SetGoalCommand(h.wanjiru, h.chat, "Holiday Fund 2027", 60_000_000_000, null)),
                ).data
            assertEquals(2, again.version)
            assertEquals(goal.createdAt, again.createdAt, "changing a target keeps when it started counting")
        }

    @Test
    fun `a member's confirmed payment into the group wallet is a contribution, once`() =
        runTest {
            val h = Harness()
            h.withWallet()
            h.goal()
            val t = TransferId.new()
            h.contribute(h.wanjiru, 1_000_000_000, t)
            h.contribute(h.wanjiru, 1_000_000_000, t) // the event arrives twice
            val view =
                assertIs<Resource.Success<com.circaso.chama.domain.models.ChamaView>>(h.getChama(h.juma, h.chat)).data
            assertEquals(1_000_000_000, view.contributedMinor)
            assertEquals(1_000_000_000, view.heldMinor)
            assertEquals(mapOf(h.wanjiru to 1_000_000_000L), view.contributorsMinor)
            assertEquals(4, view.memberCount)
            assertEquals(h.walletId, view.wallet?.walletId)
            val frame = h.frames.ofType<ChamaFrame.ContributionAdded>().single()
            assertEquals(1, h.frames.ofType<ChamaFrame.MoneyUpdated>().size, "once, even when the event arrives twice")
            assertEquals("Wanjiru", frame.displayName)
            assertEquals("1000", frame.amount.amount)
            assertEquals(1, h.bus.published.count { it.type == ContributionRecorded.TYPE })
            assertEquals(h.bus.published.size, h.outbox.written.size, "every event has its outbox row")

            // not a contribution: to no group, from a stranger, or from another group's wallet
            h.contribute(h.wanjiru, 5, to = null)
            h.contribute(UserId.new(), 5)
            h.recordTransfer(WalletTransfer(TransferId.new(), UserId.new(), 5, null, ChatId.new(), h.chat, null))
            assertEquals(1, h.contributions.rows.size)
            assertIs<Resource.Error>(h.getChama(UserId.new(), h.chat))
                .also { assertEquals(ChamaError.NotAMember, it.error) }
            assertEquals(1, assertIs<Resource.Success<List<*>>>(h.listLedger(h.juma, h.chat, 10, null)).data.size)
        }

    @Test
    fun `contributions count towards a target from when it was set, and without one are recorded quietly`() =
        runTest {
            val h = Harness()
            h.withWallet()
            h.contribute(h.juma, 300_000_000)
            assertEquals(1, h.contributions.rows.size, "recorded: the group wallet's ledger has it")
            assertTrue(h.frames.ofType<ChamaFrame.ContributionAdded>().isEmpty(), "no target, no goal frame")
            // ...but every member still hears the money moved, so the balance updates live on their phones.
            val (to, moved) = h.frames.sent.single { it.second is ChamaFrame.MoneyUpdated }
            moved as ChamaFrame.MoneyUpdated
            assertEquals(com.circaso.chama.api.MoneyUpdatedFrame.CONTRIBUTION, moved.kind)
            assertEquals(h.juma, moved.userId)
            assertEquals("300", moved.amount.amount)
            assertEquals(4, to.size, "to every member, the payer too")

            h.now = h.now.plusSeconds(60)
            h.goal()
            h.contribute(h.juma, 200_000_000)
            val money =
                assertIs<Resource.Success<com.circaso.chama.domain.models.GroupMoney>>(
                    h.getMoney(h.juma, h.chat),
                ).data
            assertEquals(200_000_000, money.contributedMinor)
            assertEquals(2, assertIs<Resource.Success<List<*>>>(h.listLedger(h.juma, h.chat, 10, null)).data.size)
        }

    @Test
    fun `legacy contributions to a treasurer stay in the ledger and count towards nothing`() =
        runTest {
            val h = Harness()
            h.goals.save(
                Goal(h.chat, "Old Fund", 10_000_000, null, h.otieno, h.otieno, h.now, h.now, 1),
            )
            h.contributions.insert(
                Contribution(TransferId.new(), h.chat, h.juma, 5_000_000, h.now.plusSeconds(1), legacy = true),
            )
            val view =
                assertIs<Resource.Success<com.circaso.chama.domain.models.ChamaView>>(h.getChama(h.juma, h.chat)).data
            assertEquals(0, view.contributedMinor)
            assertEquals(1, assertIs<Resource.Success<List<*>>>(h.listLedger(h.juma, h.chat, 10, null)).data.size)

            h.withWallet()
            val fresh = assertIs<Resource.Success<Goal>>(h.goal()).data
            assertEquals(1, fresh.version, "the old goal starts over as the group wallet's target")
            assertNull(fresh.treasurerId)
        }

    @Test
    fun `CH7 shows a group's money with or without a wallet or target`() =
        runTest {
            val h = Harness()
            val bare =
                assertIs<Resource.Success<com.circaso.chama.domain.models.GroupMoney>>(
                    h.getMoney(h.otieno, h.chat),
                ).data
            assertNull(bare.wallet)
            assertNull(bare.goal)
            assertEquals(ApprovalPolicy.TWO_ADMINS, bare.settings.approvalPolicy)
            assertTrue(bare.isOwner)
            assertFalse(bare.isSigner)

            h.withWallet()
            val money =
                assertIs<Resource.Success<com.circaso.chama.domain.models.GroupMoney>>(
                    h.getMoney(h.otieno, h.chat),
                ).data
            assertTrue(money.isSigner)
            assertFalse(
                assertIs<Resource.Success<com.circaso.chama.domain.models.GroupMoney>>(
                    h.getMoney(h.juma, h.chat),
                ).data.isOwner,
            )
            assertEquals(ChamaError.NotAMember, assertIs<Resource.Error>(h.getMoney(UserId.new(), h.chat)).error)
        }

    @Test
    fun `an admin removes the target, and the money and the ledger stay`() =
        runTest {
            val h = Harness()
            h.withWallet()
            h.goal()
            h.contribute(h.juma, 100)
            assertEquals(ChamaError.NotAdmin, assertIs<Resource.Error>(h.removeGoal(h.juma, h.chat)).error)

            assertIs<Resource.Success<Unit>>(h.removeGoal(h.wanjiru, h.chat))
            assertNull(h.goals.rows[h.chat])
            assertEquals(1, h.contributions.rows.size)
            assertEquals(1, h.frames.ofType<ChamaFrame.GroupUpdated>().size)
            assertEquals(ChamaError.NoGoal, assertIs<Resource.Error>(h.removeGoal(h.wanjiru, h.chat)).error)
            assertEquals(ChamaError.NoGoal, assertIs<Resource.Error>(h.getChama(h.juma, h.chat)).error)
        }

    @Test
    fun `a payout needs the group's active wallet, not a target`() =
        runTest {
            val h = Harness()
            assertEquals(ChamaError.NoWallet, assertIs<Resource.Error>(h.ask(h.juma)).error)
            h.withWallet()
            assertIs<Resource.Success<Payout>>(h.ask(h.juma))
        }

    @Test
    fun `a request needs two admins, an admin asking counts as one, and one decline closes it`() =
        runTest {
            val h = Harness()
            h.withWallet()
            val byMember = assertIs<Resource.Success<Payout>>(h.ask(h.juma, payee = null, address = "0xbus")).data
            assertEquals(RequestState.PENDING, byMember.state)
            assertEquals(2, byMember.approvalsNeeded)
            assertEquals(0, byMember.approvals.size)
            val pending = h.frames.ofType<ChamaFrame.RequestUpdated>().last()
            assertEquals(setOf(h.otieno, h.wanjiru), pending.waitingOn.toSet())
            assertEquals(h.otieno, pending.signerId, "the frame names who will pay it")

            assertIs<Resource.Error>(h.decideRequest(h.juma, byMember.requestId, Decision.APPROVE))
                .also { assertEquals(ChamaError.NotAdmin, it.error) }
            val one =
                assertIs<Resource.Success<Payout>>(h.decideRequest(h.otieno, byMember.requestId, Decision.APPROVE)).data
            assertEquals(RequestState.PENDING, one.state)
            assertIs<Resource.Error>(h.decideRequest(h.otieno, byMember.requestId, Decision.APPROVE))
                .also { assertEquals(ChamaError.AlreadyDecided, it.error) }
            val two =
                assertIs<Resource.Success<Payout>>(
                    h.decideRequest(h.wanjiru, byMember.requestId, Decision.APPROVE),
                ).data
            assertEquals(RequestState.APPROVED, two.state)
            assertIs<Resource.Error>(h.decideRequest(h.wanjiru, byMember.requestId, Decision.DECLINE))
                .also { assertEquals(ChamaError.RequestClosed, it.error) }

            val byAdmin = assertIs<Resource.Success<Payout>>(h.ask(h.otieno)).data
            assertEquals(1, byAdmin.approvals.size)
            assertEquals(RequestState.PENDING, byAdmin.state)
            val declined =
                assertIs<Resource.Success<Payout>>(h.decideRequest(h.wanjiru, byAdmin.requestId, Decision.DECLINE)).data
            assertEquals(RequestState.DECLINED, declined.state)

            assertEquals(ChamaError.PayeeInvalid, assertIs<Resource.Error>(h.ask(h.juma, address = "0xboth")).error)
            assertEquals(ChamaError.AmountInvalid, assertIs<Resource.Error>(h.ask(h.juma, amountMinor = 0)).error)
        }

    @Test
    fun `only the owner chooses any admin, and then an admin's own request is approved at once`() =
        runTest {
            val h = Harness()
            h.withWallet()
            assertEquals(
                ChamaError.NotOwner,
                assertIs<Resource.Error>(
                    h.setSettings(SetSettingsCommand(h.wanjiru, h.chat, ApprovalPolicy.ANY_ADMIN)),
                ).error,
            )
            assertIs<Resource.Success<GroupSettings>>(
                h.setSettings(SetSettingsCommand(h.otieno, h.chat, ApprovalPolicy.ANY_ADMIN)),
            )
            assertEquals(1, h.frames.ofType<ChamaFrame.GroupUpdated>().size, "every member sees the rule change")

            val byAdmin = assertIs<Resource.Success<Payout>>(h.ask(h.wanjiru)).data
            assertEquals(1, byAdmin.approvalsNeeded)
            assertEquals(RequestState.APPROVED, byAdmin.state)
            val byMember = assertIs<Resource.Success<Payout>>(h.ask(h.juma)).data
            assertEquals(RequestState.PENDING, byMember.state)
            assertEquals(
                RequestState.APPROVED,
                assertIs<Resource.Success<Payout>>(
                    h.decideRequest(h.otieno, byMember.requestId, Decision.APPROVE),
                ).data.state,
            )
        }

    @Test
    fun `the wallet's guard sees only approved, unpaid payouts of that group`() =
        runTest {
            val h = Harness()
            h.withWallet()
            val request = assertIs<Resource.Success<Payout>>(h.ask(h.otieno)).data
            assertNull(h.findApproved(h.chat, request.requestId.value), "pending")
            h.decideRequest(h.wanjiru, request.requestId, Decision.APPROVE)

            val approved = assertNotNull(h.findApproved(h.chat, request.requestId.value))
            assertEquals(h.achieng, approved.payeeUserId)
            assertEquals(500_000_000, approved.amountMinor)
            assertNull(h.findApproved(ChatId.new(), request.requestId.value), "another group's request")
        }

    @Test
    fun `a payment from the group wallet naming the request pays it, and is never a contribution`() =
        runTest {
            val h = Harness()
            h.withWallet()
            val request = assertIs<Resource.Success<Payout>>(h.ask(h.otieno)).data
            h.decideRequest(h.wanjiru, request.requestId, Decision.APPROVE)
            val t = TransferId.new()

            h.recordTransfer(WalletTransfer(t, h.wallet, 500_000_000, h.chat, h.chat, null, request.requestId.value))
            h.recordTransfer(WalletTransfer(t, h.wallet, 500_000_000, h.chat, h.chat, null, request.requestId.value))
            val paid = h.requests.rows.getValue(request.requestId)
            assertEquals(RequestState.PAID, paid.state)
            assertEquals(t, paid.transferId)
            val payout = h.frames.ofType<ChamaFrame.MoneyUpdated>().single()
            assertEquals(com.circaso.chama.api.MoneyUpdatedFrame.PAYOUT, payout.kind)
            assertEquals(request.requestId, payout.requestId)
            assertEquals(t.value.toString(), payout.transferId)
            assertEquals(1, h.bus.published.count { it.type == RequestPaid.TYPE })
            assertEquals(0, h.contributions.rows.size)
            assertNull(h.findApproved(h.chat, request.requestId.value), "a paid request can't be paid again")
            val view =
                assertIs<Resource.Success<com.circaso.chama.domain.models.GroupMoney>>(
                    h.getMoney(h.otieno, h.chat),
                ).data
            assertEquals(500_000_000, view.paidOutMinor)
        }

    @Test
    fun `the owner's payment straight from the group wallet is a direct payout, once, and every member hears it`() =
        runTest {
            val h = Harness()
            h.withWallet()
            h.contribute(h.juma, 900_000_000)
            h.frames.sent.clear()
            val t = TransferId.new()

            // To anyone (here outside the group: no recipient on the event), with no request.
            h.recordTransfer(WalletTransfer(t, h.wallet, 300_000_000, h.chat, h.chat, null, null))
            h.recordTransfer(WalletTransfer(t, h.wallet, 300_000_000, h.chat, h.chat, null, null))

            assertEquals(1, h.directPayouts.rows.size, "once, even when the event arrives twice")
            val sent = h.frames.sent.filter { it.second is ChamaFrame.MoneyUpdated }
            assertEquals(setOf(h.otieno, h.wanjiru, h.achieng, h.juma), sent.single().first)
            val frame = sent.single().second as ChamaFrame.MoneyUpdated
            assertEquals(com.circaso.chama.api.MoneyUpdatedFrame.PAYOUT, frame.kind)
            assertNull(frame.requestId)
            assertEquals(1, h.contributions.rows.size, "never a contribution")
            assertEquals(0, h.bus.published.count { it.type == RequestPaid.TYPE })
            val view =
                assertIs<Resource.Success<com.circaso.chama.domain.models.GroupMoney>>(
                    h.getMoney(h.wanjiru, h.chat),
                ).data
            assertEquals(300_000_000, view.paidOutMinor)
        }

    @Test
    fun `money from one group's wallet into another's is the sender's payout, no member's contribution`() =
        runTest {
            val h = Harness()
            h.withWallet()
            val other = ChatId.new()
            h.recordTransfer(WalletTransfer(TransferId.new(), UserId.new(), 5, null, other, h.chat, null))
            assertEquals(0, h.contributions.rows.size)
            assertEquals(
                other,
                h.directPayouts.rows.values
                    .single()
                    .chatId,
            )
        }

    @Test
    fun `CH6 links the group wallet's matching transfer, by its signer only`() =
        runTest {
            val h = Harness()
            h.withWallet()
            val request = assertIs<Resource.Success<Payout>>(h.ask(h.otieno)).data
            h.decideRequest(h.wanjiru, request.requestId, Decision.APPROVE)

            fun summary(
                id: TransferId,
                amount: String = "500000000",
                from: ChatId? = h.chat,
            ) = TransferSummary(
                id,
                h.wallet,
                h.achieng,
                "0xachieng",
                WalletAmount("USDC", amount, "500"),
                "SUBMITTED",
                h.chat,
                fromGroupId = from,
                signerId = h.otieno,
                payoutRequestId = request.requestId.value,
            )
            val t = TransferId.new().also { h.wallets.transfers[it] = summary(it) }
            val wrong = TransferId.new().also { h.wallets.transfers[it] = summary(it, amount = "400000000") }
            val personal = TransferId.new().also { h.wallets.transfers[it] = summary(it, from = null) }

            assertEquals(
                ChamaError.NotSigner,
                assertIs<Resource.Error>(h.attachPayment(h.wanjiru, request.requestId, t)).error,
            )
            assertEquals(
                ChamaError.TransferMismatch,
                assertIs<Resource.Error>(h.attachPayment(h.otieno, request.requestId, wrong)).error,
            )
            assertEquals(
                ChamaError.TransferMismatch,
                assertIs<Resource.Error>(h.attachPayment(h.otieno, request.requestId, personal)).error,
                "the signer's own money is not the group's",
            )

            val linked = assertIs<Resource.Success<Payout>>(h.attachPayment(h.otieno, request.requestId, t)).data
            assertEquals(RequestState.APPROVED, linked.state)
            assertEquals(t, linked.transferId)
            h.recordTransfer(WalletTransfer(t, h.wallet, 500_000_000, h.chat, h.chat, null, null))
            assertEquals(
                RequestState.PAID,
                h.requests.rows
                    .getValue(request.requestId)
                    .state,
            )
            val again = assertIs<Resource.Success<Payout>>(h.attachPayment(h.otieno, request.requestId, t)).data
            assertEquals(RequestState.PAID, again.state, "linking again after it is paid is harmless")
        }

    @Test
    fun `a request nobody decided within 48 hours expires`() =
        runTest {
            val h = Harness()
            h.withWallet()
            val request = assertIs<Resource.Success<Payout>>(h.ask(h.juma, amountMinor = 5)).data
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

    @Test
    fun `Asha lists only the chamas the user is in, and one chama by name with its ledger`() =
        runTest {
            val h = Harness()
            h.withWallet()
            h.goal()
            val stranger = UserId.new()
            val otherChat = ChatId.new()
            h.chats.members[otherChat] = setOf(stranger, h.juma)
            h.goals.save(h.goals.find(h.chat)!!.copy(chatId = otherChat, name = "Not theirs"))
            h.chats.members[ChatId.new()] = setOf(h.wanjiru, h.otieno) // a group without a goal is no chama
            h.contribute(h.wanjiru, 2_500_000_000)

            val mine =
                h
                    .listMyChamas(h.wanjiru)
                    .data()
                    .toAgentResponse()
                    .chamas
            assertEquals(listOf("Holiday Fund"), mine.map { it.name })
            val fund = mine.single()
            assertEquals("2500", fund.contributed)
            assertEquals("2500", fund.myContribution)
            assertEquals(5, fund.progressPercent)
            assertEquals("Otieno", fund.treasurer?.displayName, "the wallet's signer")
            assertTrue(fund.contributors.isEmpty(), "the list stays small")

            val one =
                h
                    .getChamaDigest(h.wanjiru, h.chat)
                    .data()
                    .toAgentResponse()
                    .chama
            assertEquals(listOf("Wanjiru"), one.contributors.map { it.person.displayName })
            assertEquals(listOf("IN"), one.recent.map { it.kind })
            assertEquals("chama.not_a_member", (h.getChamaDigest(h.wanjiru, otherChat) as Resource.Error).error.code)
        }
}

private fun <T> Resource<T>.data(): T = (this as Resource.Success).data
