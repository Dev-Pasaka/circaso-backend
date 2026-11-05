package com.circaso.spaces.di

import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.api.Databases
import com.circaso.infrastructure.api.ObjectStores
import com.circaso.infrastructure.api.RedisScopes
import com.circaso.infrastructure.api.ScheduledJob
import com.circaso.messaging.api.SpacePrivateChats
import com.circaso.shared.EventSubscription
import com.circaso.shared.Realtime
import com.circaso.shared.RealtimeModule
import com.circaso.shared.getOrNull
import com.circaso.spaces.api.SpacesFrames
import com.circaso.spaces.data.cache.RealtimeFramesImpl
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
import com.circaso.spaces.data.repositories.SpacesExpiry
import com.circaso.spaces.data.storage.ObjectStoreSpaceMedia
import com.circaso.spaces.data.tables.BansTable
import com.circaso.spaces.data.tables.InvitesTable
import com.circaso.spaces.data.tables.MediaTable
import com.circaso.spaces.data.tables.MembersTable
import com.circaso.spaces.data.tables.PaymentsTable
import com.circaso.spaces.data.tables.PostsTable
import com.circaso.spaces.data.tables.ReactionsTable
import com.circaso.spaces.data.tables.RealtimeTable
import com.circaso.spaces.data.tables.ReportsTable
import com.circaso.spaces.data.tables.RequestsTable
import com.circaso.spaces.data.tables.SpacesTable
import com.circaso.spaces.domain.errors.SpacesError
import com.circaso.spaces.domain.ports.FrameSender
import com.circaso.spaces.domain.ports.MediaStorage
import com.circaso.spaces.domain.ports.RealtimeLog
import com.circaso.spaces.domain.ports.Transactor
import com.circaso.spaces.domain.repositories.BanRepository
import com.circaso.spaces.domain.repositories.InviteRepository
import com.circaso.spaces.domain.repositories.MediaRepository
import com.circaso.spaces.domain.repositories.MemberRepository
import com.circaso.spaces.domain.repositories.PaymentRepository
import com.circaso.spaces.domain.repositories.PostRepository
import com.circaso.spaces.domain.repositories.ReportRepository
import com.circaso.spaces.domain.repositories.RequestRepository
import com.circaso.spaces.domain.repositories.SpaceRepository
import com.circaso.spaces.domain.support.Gate
import com.circaso.spaces.domain.support.Lifecycle
import com.circaso.spaces.domain.support.PostViews
import com.circaso.spaces.domain.support.SpaceViews
import com.circaso.spaces.domain.support.SpacesConfig
import com.circaso.spaces.domain.usecases.AcceptInvite
import com.circaso.spaces.domain.usecases.AnswerJoinAsk
import com.circaso.spaces.domain.usecases.CancelInvite
import com.circaso.spaces.domain.usecases.CancelJoinAsk
import com.circaso.spaces.domain.usecases.CheckHandle
import com.circaso.spaces.domain.usecases.CheckPrivateChat
import com.circaso.spaces.domain.usecases.CompleteSpaceMedia
import com.circaso.spaces.domain.usecases.ConfirmMembershipPayment
import com.circaso.spaces.domain.usecases.CreatePost
import com.circaso.spaces.domain.usecases.CreateSpace
import com.circaso.spaces.domain.usecases.DeclineInvite
import com.circaso.spaces.domain.usecases.DeleteSpace
import com.circaso.spaces.domain.usecases.FindMembershipCharge
import com.circaso.spaces.domain.usecases.FollowSpace
import com.circaso.spaces.domain.usecases.GetSpace
import com.circaso.spaces.domain.usecases.GetSpaceByHandle
import com.circaso.spaces.domain.usecases.GetSpaceName
import com.circaso.spaces.domain.usecases.GetSpaceTeam
import com.circaso.spaces.domain.usecases.GetWalletAccess
import com.circaso.spaces.domain.usecases.GoLiveWhenWalletReady
import com.circaso.spaces.domain.usecases.InvitePeople
import com.circaso.spaces.domain.usecases.LeaveSpace
import com.circaso.spaces.domain.usecases.ListAdministeredSpaces
import com.circaso.spaces.domain.usecases.ListInvitesAndRequests
import com.circaso.spaces.domain.usecases.ListMembers
import com.circaso.spaces.domain.usecases.ListMyInvites
import com.circaso.spaces.domain.usecases.ListMySpaces
import com.circaso.spaces.domain.usecases.ListOpsReports
import com.circaso.spaces.domain.usecases.ListPosts
import com.circaso.spaces.domain.usecases.ListReports
import com.circaso.spaces.domain.usecases.MarkRead
import com.circaso.spaces.domain.usecases.MuteMember
import com.circaso.spaces.domain.usecases.PrepareSpaceMedia
import com.circaso.spaces.domain.usecases.PurgeSpaceMedia
import com.circaso.spaces.domain.usecases.ReactToPost
import com.circaso.spaces.domain.usecases.RemoveAccountFromSpaces
import com.circaso.spaces.domain.usecases.RemoveMember
import com.circaso.spaces.domain.usecases.RemovePost
import com.circaso.spaces.domain.usecases.RemoveSpace
import com.circaso.spaces.domain.usecases.ReplayFrames
import com.circaso.spaces.domain.usecases.ReportContent
import com.circaso.spaces.domain.usecases.RequestToJoin
import com.circaso.spaces.domain.usecases.ResolveReport
import com.circaso.spaces.domain.usecases.RunMembershipTimers
import com.circaso.spaces.domain.usecases.SearchSpaces
import com.circaso.spaces.domain.usecases.SetBan
import com.circaso.spaces.domain.usecases.SetNotifications
import com.circaso.spaces.domain.usecases.SetRole
import com.circaso.spaces.domain.usecases.StartMembershipPayment
import com.circaso.spaces.domain.usecases.SuggestSpaces
import com.circaso.spaces.domain.usecases.UpdateSpace
import com.circaso.spaces.presentation.api.SpacePrivateChatsImpl
import com.circaso.spaces.presentation.api.SpaceWalletPolicyImpl
import com.circaso.spaces.presentation.api.SpacesRealtimeReplay
import com.circaso.spaces.presentation.controllers.inviteController
import com.circaso.spaces.presentation.controllers.memberController
import com.circaso.spaces.presentation.controllers.postController
import com.circaso.spaces.presentation.controllers.reportController
import com.circaso.spaces.presentation.controllers.spaceController
import com.circaso.spaces.presentation.events.IdentityEventHandler
import com.circaso.spaces.presentation.events.WalletEventHandler
import com.circaso.spaces.presentation.services.EventsService
import com.circaso.spaces.presentation.services.InviteService
import com.circaso.spaces.presentation.services.JobsService
import com.circaso.spaces.presentation.services.MemberService
import com.circaso.spaces.presentation.services.MembershipService
import com.circaso.spaces.presentation.services.PolicyService
import com.circaso.spaces.presentation.services.PostService
import com.circaso.spaces.presentation.services.RealtimeService
import com.circaso.spaces.presentation.services.ReportService
import com.circaso.spaces.presentation.services.SpaceService
import com.circaso.wallet.api.SpaceWalletPolicy
import com.circaso.web.RouteContributor
import com.circaso.web.unavailable
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.time.Clock
import java.time.Duration

/** Schema `spaces`: its tables and its Flyway scripts. It publishes no events, so it has no outbox. */
internal val spacesDatabaseSpec =
    DatabaseModuleSpec(
        schema = "spaces",
        tables =
            listOf(
                SpacesTable,
                MembersTable,
                BansTable,
                RequestsTable,
                InvitesTable,
                PaymentsTable,
                MediaTable,
                PostsTable,
                ReactionsTable,
                ReportsTable,
                RealtimeTable,
            ),
        migrationsLocation = "classpath:db/spaces",
    )

/** Redis prefix `spc:` (`spc:seq:{userId}`). */
private const val REDIS_PREFIX = "spc"

private val MEMBERSHIPS_INTERVAL: Duration = Duration.ofMinutes(10)
private val PURGE_INTERVAL: Duration = Duration.ofMinutes(30)

/** `SPACES_ENABLED`: Spaces is archived (decision 2026-11-05) and off unless this is `true`. The wallet reads it too. */
const val SPACES_ENABLED_KEY = "spaces.enabled"

/** Every path Spaces serves under `/v1`: the app's (SP1-SP37) and Circaso ops' (SPO1-SPO2). */
internal val SPACES_PATHS = listOf("/spaces", "/ops/spaces")

/**
 * Spaces' Koin bindings. Spaces is archived (decision 2026-11-05): unless `SPACES_ENABLED=true` it is
 * [archivedSpacesModule], otherwise [liveSpacesModule].
 */
fun spacesModule(config: Config): Module =
    if (config.boolean(SPACES_ENABLED_KEY, false)) liveSpacesModule() else archivedSpacesModule()

/**
 * Spaces switched off. Only its database spec stays, so its tables are kept and still migrated (nothing is dropped),
 * and one route answers `spaces.unavailable` (404) for every Spaces path. No jobs, no `events:wallet` or
 * `events:identity` consumers, no replay and so no frames or pushes; and no `SpaceWalletPolicy` or
 * `SpacePrivateChats`, so the wallet offers no space wallet and messaging treats a chat opened via a space as an
 * ordinary direct chat.
 */
internal fun archivedSpacesModule(): Module =
    module {
        single(named("spaces.spec")) { spacesDatabaseSpec }
        single<RouteContributor>(named("spaces.routes")) {
            RouteContributor { route -> SPACES_PATHS.forEach { route.unavailable(it, SpacesError.Unavailable) } }
        }
    }

/**
 * Spaces switched on: its database spec, jobs (`spaces.memberships`, `spaces.purge-expired`), routes (SP1-SP37,
 * SPO1-SPO2), its `events:wallet` and `events:identity` subscriptions, its replay, and the guards it implements for
 * the wallet (`SpaceWalletPolicy`) and messaging (`SpacePrivateChats`).
 */
internal fun liveSpacesModule(): Module =
    module {
        val clock = Clock.systemUTC()
        val spaces = SpacesConfig()
        single { spaces }

        // data
        single(named("spaces.spec")) { spacesDatabaseSpec }
        single(named("spaces.database")) { get<Databases>().scope(spacesDatabaseSpec.schema) }
        single<Transactor> { DatabaseTransactor(get(named("spaces.database"))) }
        single<SpaceRepository> { SpaceRepositoryImpl(get(named("spaces.database"))) }
        single<MemberRepository> { MemberRepositoryImpl(get(named("spaces.database"))) }
        single<BanRepository> { BanRepositoryImpl(get(named("spaces.database"))) }
        single<RequestRepository> { RequestRepositoryImpl(get(named("spaces.database"))) }
        single<InviteRepository> { InviteRepositoryImpl(get(named("spaces.database"))) }
        single<PaymentRepository> { PaymentRepositoryImpl(get(named("spaces.database"))) }
        single<PostRepository> { PostRepositoryImpl(get(named("spaces.database"))) }
        single<MediaRepository> { MediaRepositoryImpl(get(named("spaces.database"))) }
        single<ReportRepository> { ReportRepositoryImpl(get(named("spaces.database"))) }
        single<MediaStorage> { ObjectStoreSpaceMedia(get<ObjectStores>().scope("spaces")) }
        single {
            RealtimeFramesImpl(
                get<RedisScopes>().scope(REDIS_PREFIX),
                get(named("spaces.database")),
                get<Realtime>(),
                clock,
            )
        }
        single<FrameSender> { get<RealtimeFramesImpl>() }
        single<RealtimeLog> { get<RealtimeFramesImpl>() }
        single(named("spaces.purge-expired")) {
            val expiry = SpacesExpiry(get(named("spaces.database")))
            val jobs = get<JobsService>()
            ScheduledJob("spaces.purge-expired", PURGE_INTERVAL) { expiry.run() + (jobs.purgeMedia().getOrNull() ?: 0) }
        }
        single(named("spaces.memberships")) {
            val jobs = get<JobsService>()
            ScheduledJob("spaces.memberships", MEMBERSHIPS_INTERVAL) { jobs.memberships().getOrNull() ?: 0 }
        }

        // domain
        single { Gate(get(), get(), get()) }
        single { SpaceViews(get(), get(), get(), get(), get(), get(), get(), get(), spaces, clock) }
        single { PostViews(get(), get(), get(), get(), get(), spaces) }
        single { Lifecycle(get(), get(), get(), get(), clock) }
        single { CheckHandle(get()) }
        single { CreateSpace(get(), get(), get(), get(), get(), get(), get(), get(), spaces, clock) }
        single { GetSpace(get(), get(), get(), get()) }
        single { GetSpaceByHandle(get(), get(), get(), get()) }
        single { UpdateSpace(get(), get(), get(), get(), get(), get(), get(), get(), spaces, clock) }
        single { DeleteSpace(get(), get(), get(), get(), get(), get(), clock) }
        single { ListMySpaces(get(), get(), get(), get(), get(), get(), spaces) }
        single { SuggestSpaces(get(), get(), get(), get()) }
        single { SearchSpaces(get(), get()) }
        single { FollowSpace(get(), get(), get(), get(), get(), get(), clock) }
        single { LeaveSpace(get(), get(), get(), get()) }
        single { SetNotifications(get(), get(), get()) }
        single { StartMembershipPayment(get(), get(), get(), get(), get(), spaces, clock) }
        single { ConfirmMembershipPayment(get(), get(), get(), get(), get(), clock) }
        single { RunMembershipTimers(get(), get(), get(), spaces, clock) }
        single { GoLiveWhenWalletReady(get(), get()) }
        single { RequestToJoin(get(), get(), get(), get(), get(), get(), get(), get(), get(), spaces, clock) }
        single { CancelJoinAsk(get(), get(), get(), clock) }
        single { ListInvitesAndRequests(get(), get(), get(), get(), get(), spaces, clock) }
        single { AnswerJoinAsk(get(), get(), get(), get(), get(), get(), get(), clock) }
        single { InvitePeople(get(), get(), get(), get(), get(), get(), get(), spaces, clock) }
        single { CancelInvite(get(), get(), get(), clock) }
        single { ListMyInvites(get(), get(), get(), get(), get(), clock) }
        single { AcceptInvite(get(), get(), get(), get(), get(), get(), get(), get(), clock) }
        single { DeclineInvite(get(), clock) }
        single { ListMembers(get(), get(), get(), get(), get(), clock) }
        single { SetRole(get(), get(), get(), get()) }
        single { MuteMember(get(), get(), get(), get(), clock) }
        single { RemoveMember(get(), get(), get(), get(), get()) }
        single { SetBan(get(), get(), get(), get(), get(), get(), get(), clock) }
        single { ListPosts(get(), get(), get(), spaces) }
        single { CreatePost(get(), get(), get(), get(), get(), get(), get(), get(), spaces, clock) }
        single { RemovePost(get(), get(), get(), get(), get(), clock) }
        single { ReactToPost(get(), get(), get(), get(), clock) }
        single { MarkRead(get(), get(), get(), spaces) }
        single { PrepareSpaceMedia(get(), get(), spaces, clock) }
        single { CompleteSpaceMedia(get(), get(), clock) }
        single { PurgeSpaceMedia(get(), get(), clock) }
        single { ReportContent(get(), get(), get(), get(), get(), spaces, clock) }
        single { ListReports(get(), get(), get(), get(), get()) }
        single { ResolveReport(get(), get(), get(), get(), get(), get(), get(), clock) }
        single { ListOpsReports(get(), get(), get()) }
        single { RemoveSpace(get(), get(), clock) }
        single { GetWalletAccess(get(), get(), get()) }
        single { ListAdministeredSpaces(get(), get()) }
        single { GetSpaceTeam(get()) }
        single { FindMembershipCharge(get(), clock) }
        single { CheckPrivateChat(get(), get(), get()) }
        single { GetSpaceName(get()) }
        single { RemoveAccountFromSpaces(get(), get(), get(), get(), clock) }
        single { ReplayFrames(get()) }

        // presentation
        single { SpaceService(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        single { MembershipService(get(), get(), get(), get()) }
        single { InviteService(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        single { MemberService(get(), get(), get(), get(), get()) }
        single { PostService(get(), get(), get(), get(), get(), get(), get()) }
        single { ReportService(get(), get(), get(), get(), get()) }
        single { PolicyService(get(), get(), get(), get(), get(), get()) }
        single { EventsService(get(), get(), get()) }
        single { JobsService(get(), get()) }
        single { RealtimeService(get()) }
        single<RouteContributor>(named("spaces.routes")) {
            val spaceService = get<SpaceService>()
            val membership = get<MembershipService>()
            val invites = get<InviteService>()
            val members = get<MemberService>()
            val posts = get<PostService>()
            val reports = get<ReportService>()
            RouteContributor { route ->
                route.spaceController(
                    spaceService,
                    membership,
                    spaces.pageDefault,
                    spaces.pageMax,
                    spaces.suggestedDefault,
                )
                route.inviteController(invites)
                route.memberController(members, spaces.pageDefault, spaces.pageMax)
                route.postController(posts, spaces.pageDefault, spaces.pageMax)
                route.reportController(reports, spaces.pageDefault, spaces.pageMax)
            }
        }
        single<EventSubscription>(named("spaces.wallet-events")) { WalletEventHandler(get()).subscription() }
        single<EventSubscription>(named("spaces.identity-events")) { IdentityEventHandler(get()).subscription() }
        single(named("spaces.replay")) { RealtimeModule(SpacesFrames.MODULE, SpacesRealtimeReplay(get())) }
        single<SpaceWalletPolicy> { SpaceWalletPolicyImpl(get()) }
        single<SpacePrivateChats> { SpacePrivateChatsImpl(get()) }
    }
