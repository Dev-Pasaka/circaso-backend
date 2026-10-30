package com.circaso.assistant.di

import com.circaso.assistant.data.cache.AesTextVault
import com.circaso.assistant.data.cache.RedisPendingTurns
import com.circaso.assistant.data.cache.RedisQuotaCounters
import com.circaso.assistant.data.metrics.TelemetryLiveTimings
import com.circaso.assistant.data.repositories.AssistantExpiry
import com.circaso.assistant.data.repositories.AssistantStoreImpl
import com.circaso.assistant.data.repositories.ChatRepositoryImpl
import com.circaso.assistant.data.repositories.DatabaseTransactor
import com.circaso.assistant.data.repositories.EntitlementRepositoryImpl
import com.circaso.assistant.data.tables.AshaMessagesTable
import com.circaso.assistant.data.tables.AuditTable
import com.circaso.assistant.data.tables.EntitlementsTable
import com.circaso.assistant.data.tables.UsageDailyTable
import com.circaso.assistant.data.tools.HttpAgentTools
import com.circaso.assistant.domain.ports.AgentTools
import com.circaso.assistant.domain.ports.LiveTimings
import com.circaso.assistant.domain.ports.PendingTurns
import com.circaso.assistant.domain.ports.QuotaCounters
import com.circaso.assistant.domain.ports.TextVault
import com.circaso.assistant.domain.ports.Transactor
import com.circaso.assistant.domain.repositories.AssistantStore
import com.circaso.assistant.domain.repositories.ChatRepository
import com.circaso.assistant.domain.repositories.EntitlementRepository
import com.circaso.assistant.domain.support.AshaAgent
import com.circaso.assistant.domain.support.AssistantConfig
import com.circaso.assistant.domain.support.DailyUsage
import com.circaso.assistant.domain.support.OrderedIds
import com.circaso.assistant.domain.support.TurnRecorder
import com.circaso.assistant.domain.usecases.CheckLiveSession
import com.circaso.assistant.domain.usecases.ContinueTurn
import com.circaso.assistant.domain.usecases.DeleteAssistantData
import com.circaso.assistant.domain.usecases.DeleteChat
import com.circaso.assistant.domain.usecases.DeleteExchange
import com.circaso.assistant.domain.usecases.GetUsage
import com.circaso.assistant.domain.usecases.ListChat
import com.circaso.assistant.domain.usecases.MeterLiveVoice
import com.circaso.assistant.domain.usecases.OpenLiveVoice
import com.circaso.assistant.domain.usecases.RecordLiveTiming
import com.circaso.assistant.domain.usecases.SaveLiveVoiceUsage
import com.circaso.assistant.domain.usecases.StartTurn
import com.circaso.assistant.domain.usecases.SynthesizeSpeech
import com.circaso.assistant.domain.usecases.TranscribeVoice
import com.circaso.assistant.presentation.controllers.chatController
import com.circaso.assistant.presentation.controllers.liveVoiceController
import com.circaso.assistant.presentation.controllers.voiceController
import com.circaso.assistant.presentation.events.IdentityEventHandler
import com.circaso.assistant.presentation.services.ChatService
import com.circaso.assistant.presentation.services.IdentityEventsService
import com.circaso.assistant.presentation.services.LiveVoiceService
import com.circaso.assistant.presentation.services.VoiceService
import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.api.Databases
import com.circaso.infrastructure.api.HttpClients
import com.circaso.infrastructure.api.RedisScopes
import com.circaso.infrastructure.api.ScheduledJob
import com.circaso.infrastructure.api.Secrets
import com.circaso.observability.api.Telemetry
import com.circaso.shared.EventSubscription
import com.circaso.web.RouteContributor
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.time.Clock
import java.time.Duration

/** Schema `assistant`: its tables, its Flyway scripts and its outbox, which is infrastructure's (`outbox = true`). */
internal val assistantDatabaseSpec =
    DatabaseModuleSpec(
        schema = "assistant",
        tables = listOf(AshaMessagesTable, UsageDailyTable, EntitlementsTable, AuditTable),
        migrationsLocation = "classpath:db/assistant",
        outbox = true,
    )

/** How often `assistant.purge-expired` runs: it only keeps the outbox partitions a day ahead, so this is plenty. */
private val PURGE_INTERVAL: Duration = Duration.ofMinutes(5)

/**
 * The assistant's Koin bindings: data, domain, presentation, its database spec (with the outbox), its purge job and its
 * event subscription.
 */
fun assistantModule(config: Config): Module =
    module {
        val clock = Clock.systemUTC()
        val assistant = config.section("assistant")
        val local = config.environment.isLocal
        val assistantConfig = AssistantConfig()

        single { assistantConfig }

        // data
        single(named("assistant.spec")) { assistantDatabaseSpec }
        single(named("assistant.database")) { get<Databases>().scope(assistantDatabaseSpec.schema) }
        single<Transactor> { DatabaseTransactor(get(named("assistant.database"))) }
        single<TextVault> { AesTextVault(get<Secrets>(), local) }
        single<QuotaCounters> { RedisQuotaCounters(get<RedisScopes>().scope("ast")) }
        single<PendingTurns> { RedisPendingTurns(get<RedisScopes>().scope("ast")) }
        single<LiveTimings> { TelemetryLiveTimings(get<Telemetry>()) }
        single<ChatRepository> { ChatRepositoryImpl(get(named("assistant.database")), get()) }
        single<EntitlementRepository> { EntitlementRepositoryImpl(get(named("assistant.database")), clock) }
        single<AssistantStore> { AssistantStoreImpl(get(named("assistant.database")), get(), clock) }
        single(named("assistant.purge-expired")) {
            val expiry = AssistantExpiry(get(named("assistant.database")))
            ScheduledJob("assistant.purge-expired", PURGE_INTERVAL) { expiry.run() }
        }
        single<AgentTools> {
            HttpAgentTools(
                get<HttpClients>().named("agent"),
                assistant
                    .string(
                        "agent-base-url",
                        "http://localhost:" + config.string("port", "8080"),
                    ).trimEnd('/'),
            )
        }

        // domain
        single { AshaAgent(get(), get(), get()) }
        single { OrderedIds(clock) }
        single { DailyUsage(get(), get(), get(), clock) }
        single { TurnRecorder(get(), get(), get(), get(), get(), get(), clock) }
        single { StartTurn(get(), get(), get(), get(), get(), get(), get(), get(), clock) }
        single { ContinueTurn(get(), get(), get(), get(), get()) }
        single { ListChat(get()) }
        single { DeleteChat(get(), get()) }
        single { DeleteExchange(get(), get(), get()) }
        single { GetUsage(get(), get(), get(), clock) }
        single { TranscribeVoice(get(), get(), get(), get(), get(), clock) }
        single { SynthesizeSpeech(get(), get(), get(), get(), get(), clock) }
        single { DeleteAssistantData(get(), get(), get(), get(), clock) }
        single { OpenLiveVoice(get(), get(), get(), get(), get(), get(), clock) }
        single { MeterLiveVoice(get(), clock) }
        single { SaveLiveVoiceUsage(get()) }
        single { CheckLiveSession(get()) }
        single { RecordLiveTiming(get()) }

        // presentation
        single { ChatService(get(), get(), get(), get(), get()) }
        single { VoiceService(get(), get(), get()) }
        single { IdentityEventsService(get()) }
        single { LiveVoiceService(get(), get(), get(), get(), get(), get(), get()) }
        single<RouteContributor>(named("assistant.routes")) {
            val chat = get<ChatService>()
            val voice = get<VoiceService>()
            val live = get<LiveVoiceService>()
            RouteContributor { route ->
                route.chatController(chat)
                route.voiceController(voice)
                route.liveVoiceController(live)
            }
        }
        single<EventSubscription>(named("assistant.identity-events")) { IdentityEventHandler(get()).subscription() }
    }
