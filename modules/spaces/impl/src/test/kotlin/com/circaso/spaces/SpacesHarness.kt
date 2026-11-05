package com.circaso.spaces

import com.circaso.identity.api.AccountDirectory
import com.circaso.identity.api.AccountKind
import com.circaso.identity.api.AccountStatus
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.messaging.api.ChatId
import com.circaso.shared.DomainError
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.spaces.api.SpaceFrame
import com.circaso.spaces.api.SpaceId
import com.circaso.spaces.data.repositories.BanRepositoryImpl
import com.circaso.spaces.data.repositories.DatabaseTransactor
import com.circaso.spaces.data.repositories.InviteRepositoryImpl
import com.circaso.spaces.data.repositories.MediaRepositoryImpl
import com.circaso.spaces.data.repositories.MemberRepositoryImpl
import com.circaso.spaces.data.repositories.PaymentRepositoryImpl
import com.circaso.spaces.data.repositories.PostRepositoryImpl
import com.circaso.spaces.data.repositories.ReportRepositoryImpl
import com.circaso.spaces.data.repositories.RequestRepositoryImpl
import com.circaso.spaces.data.repositories.SpaceRepositoryImpl
import com.circaso.spaces.di.spacesDatabaseSpec
import com.circaso.spaces.domain.models.CreateSpaceCommand
import com.circaso.spaces.domain.models.PostingMode
import com.circaso.spaces.domain.models.Price
import com.circaso.spaces.domain.models.PrivateMessages
import com.circaso.spaces.domain.models.SpaceView
import com.circaso.spaces.domain.models.TeamEntry
import com.circaso.spaces.domain.models.Topic
import com.circaso.spaces.domain.models.Visibility
import com.circaso.spaces.domain.ports.FrameSender
import com.circaso.spaces.domain.ports.MediaStorage
import com.circaso.spaces.domain.support.Gate
import com.circaso.spaces.domain.support.Lifecycle
import com.circaso.spaces.domain.support.PostViews
import com.circaso.spaces.domain.support.SpaceViews
import com.circaso.spaces.domain.support.SpacesConfig
import com.circaso.spaces.domain.usecases.AcceptInvite
import com.circaso.spaces.domain.usecases.AnswerJoinAsk
import com.circaso.spaces.domain.usecases.CheckHandle
import com.circaso.spaces.domain.usecases.CheckPrivateChat
import com.circaso.spaces.domain.usecases.ConfirmMembershipPayment
import com.circaso.spaces.domain.usecases.CreatePost
import com.circaso.spaces.domain.usecases.CreateSpace
import com.circaso.spaces.domain.usecases.DeclineInvite
import com.circaso.spaces.domain.usecases.FindMembershipCharge
import com.circaso.spaces.domain.usecases.FollowSpace
import com.circaso.spaces.domain.usecases.GetSpace
import com.circaso.spaces.domain.usecases.GetWalletAccess
import com.circaso.spaces.domain.usecases.GoLiveWhenWalletReady
import com.circaso.spaces.domain.usecases.InvitePeople
import com.circaso.spaces.domain.usecases.LeaveSpace
import com.circaso.spaces.domain.usecases.ListInvitesAndRequests
import com.circaso.spaces.domain.usecases.ListMembers
import com.circaso.spaces.domain.usecases.ListMySpaces
import com.circaso.spaces.domain.usecases.ListPosts
import com.circaso.spaces.domain.usecases.ListReports
import com.circaso.spaces.domain.usecases.MarkRead
import com.circaso.spaces.domain.usecases.MuteMember
import com.circaso.spaces.domain.usecases.ReactToPost
import com.circaso.spaces.domain.usecases.RemovePost
import com.circaso.spaces.domain.usecases.ReportContent
import com.circaso.spaces.domain.usecases.RequestToJoin
import com.circaso.spaces.domain.usecases.ResolveReport
import com.circaso.spaces.domain.usecases.RunMembershipTimers
import com.circaso.spaces.domain.usecases.SearchSpaces
import com.circaso.spaces.domain.usecases.SetBan
import com.circaso.spaces.domain.usecases.SetRole
import com.circaso.spaces.domain.usecases.StartMembershipPayment
import com.circaso.spaces.domain.usecases.SuggestSpaces
import com.circaso.spaces.domain.usecases.UpdateSpace
import com.circaso.users.api.ContactBook
import com.circaso.users.api.Profiles
import com.circaso.users.api.PublicProfile
import com.circaso.users.api.ReachPolicy
import com.circaso.wallet.api.GroupWalletSummary
import com.circaso.wallet.api.SpaceWalletSummary
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.api.TransferSummary
import com.circaso.wallet.api.WalletId
import com.circaso.wallet.api.Wallets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** A clock the test moves. */
class MovableClock(var now: Instant = Instant.parse("2026-11-04T09:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now

    fun advance(by: Duration) {
        now = now.plus(by)
    }
}

class FakeAccounts(private val clock: Clock) : AccountDirectory {
    val created = mutableMapOf<UserId, Instant>()

    override suspend fun status(userId: UserId) = AccountStatus.ACTIVE

    override suspend fun kind(userId: UserId) = AccountKind.PERSON

    override suspend fun resolvePhones(phones: Set<PhoneNumber>) = emptyMap<PhoneNumber, UserId>()

    override suspend fun createdAt(userId: UserId): Instant =
        created[userId] ?: clock.instant().minus(Duration.ofDays(30))
}

class FakeReach : ReachPolicy {
    val blocked = mutableSetOf<Set<UserId>>()

    override suspend fun canReach(from: UserId, to: UserId) = from != to && setOf(from, to) !in blocked

    override suspend fun reachable(from: UserId, to: Set<UserId>) = to.filterTo(mutableSetOf()) { canReach(from, it) }

    override suspend fun blockedEitherWay(a: UserId, b: UserId) = setOf(a, b) in blocked
}

class FakeProfiles : Profiles {
    val names = mutableMapOf<UserId, String>()

    override suspend fun get(ids: Set<UserId>) =
        ids.mapNotNull { id -> names[id]?.let { id to PublicProfile(id, it, it.lowercase(), null, null) } }.toMap()
}

class FakeContacts : ContactBook {
    val contacts = mutableMapOf<UserId, MutableSet<UserId>>()

    override suspend fun contactsOf(userId: UserId, limit: Int): Set<UserId> = contacts[userId].orEmpty()
}

class FakeWallets : Wallets {
    val spaceWallets = mutableMapOf<UUID, SpaceWalletSummary>()
    var holdsMoney = false

    override suspend fun hasWallet(userId: UserId) = true

    override suspend fun transfer(transferId: TransferId): TransferSummary? = null

    override suspend fun groupWallet(chatId: ChatId): GroupWalletSummary? = null

    override suspend fun spaceWallet(spaceId: UUID): SpaceWalletSummary? = spaceWallets[spaceId]

    override suspend fun spaceWalletHoldsMoney(spaceId: UUID): Resource<Boolean> = Resource.success(holdsMoney)

    fun ready(spaceId: SpaceId, owner: UserId) {
        spaceWallets[spaceId.value] =
            SpaceWalletSummary(WalletId.new(), spaceId.value, "ACTIVE", owner, listOf("BASE-SEPOLIA"))
    }
}

class MemoryStorage : MediaStorage {
    val objects = mutableMapOf<String, Long>()

    override suspend fun prepareUpload(
        key: String,
        contentType: String,
        expiresIn: Duration,
    ): Resource<String> = Resource.success("https://storage.test/$key")

    override suspend fun sizeOf(key: String): Long? = objects[key]

    override suspend fun downloadUrl(key: String, expiresIn: Duration): String = "https://storage.test/$key?get"

    override suspend fun delete(key: String) {
        objects.remove(key)
    }
}

class RecordingFrames : FrameSender {
    val sent = mutableListOf<Pair<Set<UserId>, SpaceFrame>>()

    override suspend fun send(userIds: Collection<UserId>, frame: SpaceFrame) {
        if (userIds.isNotEmpty()) sent += userIds.toSet() to frame
    }

    inline fun <reified T : SpaceFrame> of(): List<Pair<Set<UserId>, T>> =
        sent.mapNotNull { (u, f) ->
            (f as? T)?.let {
                u to
                    it
            }
        }
}

/** Spaces' use cases over its real PostgreSQL tables, with fakes for every other module and the clock. */
class SpacesHarness(val config: SpacesConfig = SpacesConfig()) {
    val clock = MovableClock()
    private val database = TestPostgres.database(spacesDatabaseSpec)
    val spaces = SpaceRepositoryImpl(database)
    val members = MemberRepositoryImpl(database)
    val bans = BanRepositoryImpl(database)
    val requests = RequestRepositoryImpl(database)
    val invites = InviteRepositoryImpl(database)
    val payments = PaymentRepositoryImpl(database)
    val posts = PostRepositoryImpl(database)
    val media = MediaRepositoryImpl(database)
    val reports = ReportRepositoryImpl(database)
    val transactor = DatabaseTransactor(database)

    val accounts = FakeAccounts(clock)
    val reach = FakeReach()
    val profiles = FakeProfiles()
    val contacts = FakeContacts()
    val wallets = FakeWallets()
    val storage = MemoryStorage()
    val frames = RecordingFrames()

    val gate = Gate(spaces, members, bans)
    val views = SpaceViews(members, requests, invites, posts, profiles, contacts, wallets, storage, config, clock)
    val postViews = PostViews(posts, members, media, profiles, storage, config)
    val lifecycle = Lifecycle(spaces, members, wallets, frames, clock)

    val checkHandle = CheckHandle(spaces)
    val create = CreateSpace(spaces, members, media, transactor, accounts, reach, views, frames, config, clock)
    val get = GetSpace(spaces, gate, views, lifecycle)
    val update = UpdateSpace(spaces, members, media, transactor, gate, views, lifecycle, frames, config, clock)
    val mine = ListMySpaces(spaces, members, bans, posts, views, profiles, config)
    val suggest = SuggestSpaces(spaces, members, bans, views)
    val search = SearchSpaces(spaces, views)
    val follow = FollowSpace(spaces, members, transactor, gate, accounts, views, clock)
    val leave = LeaveSpace(spaces, members, transactor, gate)
    val startPayment = StartMembershipPayment(payments, gate, views, wallets, accounts, config, clock)
    val confirmPayment = ConfirmMembershipPayment(spaces, members, payments, transactor, frames, clock)
    val timers = RunMembershipTimers(spaces, members, frames, config, clock)
    val goLive = GoLiveWhenWalletReady(spaces, lifecycle)
    val request =
        RequestToJoin(spaces, members, requests, invites, transactor, gate, views, profiles, frames, config, clock)
    val listInvites = ListInvitesAndRequests(members, requests, invites, gate, profiles, config, clock)
    val answer = AnswerJoinAsk(spaces, members, requests, bans, transactor, gate, frames, clock)
    val invite = InvitePeople(members, bans, invites, gate, reach, profiles, frames, config, clock)
    val accept = AcceptInvite(spaces, members, invites, bans, transactor, views, profiles, frames, clock)
    val decline = DeclineInvite(invites, clock)
    val listMembers = ListMembers(members, bans, gate, views, profiles, clock)
    val setRole = SetRole(members, gate, profiles, frames)
    val mute = MuteMember(members, gate, profiles, frames, clock)
    val ban = SetBan(spaces, members, bans, invites, transactor, gate, frames, clock)
    val listPosts = ListPosts(posts, gate, postViews, config)
    val post = CreatePost(spaces, members, posts, media, transactor, gate, postViews, frames, config, clock)
    val removePost = RemovePost(members, posts, gate, postViews, frames, clock)
    val react = ReactToPost(members, posts, gate, frames, clock)
    val markRead = MarkRead(members, posts, gate, config)
    val report = ReportContent(members, posts, reports, gate, frames, config, clock)
    val listReports = ListReports(posts, reports, gate, postViews, profiles)
    val resolve = ResolveReport(spaces, members, posts, reports, transactor, gate, frames, clock)
    val walletAccess = GetWalletAccess(spaces, members, bans)
    val charge = FindMembershipCharge(payments, clock)
    val privateChat = CheckPrivateChat(spaces, members, bans)

    fun person(name: String): UserId = UserId.new().also { profiles.names[it] = name }

    fun command(
        owner: UserId,
        name: String = "Nairobi Sneaker Drop",
        handle: String = "sneakerdrop",
        visibility: Visibility = Visibility.PUBLIC,
        price: Price? = null,
        team: List<TeamEntry> = emptyList(),
        postingMode: PostingMode = PostingMode.EVERYONE,
        key: String = UUID.randomUUID().toString(),
        about: String = "Original sneakers in Nairobi CBD",
        topic: Topic = Topic.SHOPPING,
    ) = CreateSpaceCommand(
        ownerId = owner,
        name = name,
        handle = handle,
        about = about,
        topic = topic,
        photoMediaId = null,
        coverMediaId = null,
        visibility = visibility,
        price = price,
        postingMode = postingMode,
        privateMessages = PrivateMessages.ANYONE,
        team = team,
        country = "KE",
        createKey = key,
    )

    /** A space made and taken live (its wallet ready). */
    suspend fun liveSpace(owner: UserId, command: CreateSpaceCommand = command(owner)): SpaceId {
        val view = create(command).data()
        val id = view.summary.space.spaceId
        wallets.ready(id, owner)
        goLive(id).data()
        return id
    }

    suspend fun spaceOf(caller: UserId, id: SpaceId): SpaceView = get(caller, id).data()
}

fun <T> Resource<T>.data(): T = assertIs<Resource.Success<T>>(this).data

fun Resource<*>.assertError(code: String) {
    assertEquals(code, assertIs<Resource.Error>(this).error.code)
}

fun Resource<*>.assertError(expected: DomainError) = assertError(expected.code)
