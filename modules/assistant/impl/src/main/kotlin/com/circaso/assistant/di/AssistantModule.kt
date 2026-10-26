package com.circaso.assistant.di

import com.circaso.assistant.data.cache.AesTextVault
import com.circaso.assistant.data.cache.RedisPendingTurns
import com.circaso.assistant.data.cache.RedisQuotaCounters
import com.circaso.assistant.data.entities.AshaMessageRow
import com.circaso.assistant.data.entities.AssistantAuditRow
import com.circaso.assistant.data.entities.AssistantOutboxRow
import com.circaso.assistant.data.entities.EntitlementRow
import com.circaso.assistant.data.entities.UsageDailyRow
import com.circaso.assistant.data.migrations.V001_CreateAssistantTables
import com.circaso.assistant.data.migrations.assistantEntities
import com.circaso.assistant.data.repositories.AssistantOutboxStore
import com.circaso.assistant.data.repositories.AssistantStoreImpl
import com.circaso.assistant.data.repositories.ChatRepositoryImpl
import com.circaso.assistant.data.repositories.EntitlementRepositoryImpl
import com.circaso.assistant.data.rows
import com.circaso.assistant.data.tools.HttpAgentTools
import com.circaso.assistant.domain.ports.AgentTools
import com.circaso.assistant.domain.ports.PendingTurns
import com.circaso.assistant.domain.ports.QuotaCounters
import com.circaso.assistant.domain.ports.TextVault
import com.circaso.assistant.domain.repositories.AssistantStore
import com.circaso.assistant.domain.repositories.ChatRepository
import com.circaso.assistant.domain.repositories.EntitlementRepository
import com.circaso.assistant.domain.support.AshaAgent
import com.circaso.assistant.domain.support.AssistantConfig
import com.circaso.assistant.domain.support.OrderedIds
import com.circaso.assistant.domain.support.TurnRecorder
import com.circaso.assistant.domain.usecases.ContinueTurn
import com.circaso.assistant.domain.usecases.DeleteAssistantData
import com.circaso.assistant.domain.usecases.DeleteChat
import com.circaso.assistant.domain.usecases.DeleteExchange
import com.circaso.assistant.domain.usecases.GetUsage
import com.circaso.assistant.domain.usecases.ListChat
import com.circaso.assistant.domain.usecases.MeterLiveVoice
import com.circaso.assistant.domain.usecases.OpenLiveVoice
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
import com.circaso.infrastructure.api.HttpClients
import com.circaso.infrastructure.api.KandraModuleSpec
import com.circaso.infrastructure.api.OutboxStore
import com.circaso.infrastructure.api.RedisScopes
import com.circaso.infrastructure.api.Secrets
import com.circaso.shared.EventSubscription
import com.circaso.web.RouteContributor
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.time.Clock

/** The assistant's Koin bindings: data, domain, presentation, its Kandra spec, its outbox and its event subscription. */
fun assistantModule(config: Config): Module =
    module {
        val clock = Clock.systemUTC()
        val assistant = config.section("assistant")
        val local = config.environment.isLocal
        val assistantConfig = AssistantConfig()

        single { assistantConfig }

        // data
        single<TextVault> { AesTextVault(get<Secrets>(), local) }
        single<QuotaCounters> { RedisQuotaCounters(get<RedisScopes>().scope("ast")) }
        single<PendingTurns> { RedisPendingTurns(get<RedisScopes>().scope("ast")) }
        single<ChatRepository> { ChatRepositoryImpl(rows<AshaMessageRow>(), get()) }
        single<EntitlementRepository> { EntitlementRepositoryImpl(rows<EntitlementRow>()) }
        single<AssistantStore> {
            AssistantStoreImpl(
                get(),
                rows<AshaMessageRow>(),
                rows<UsageDailyRow>(),
                rows<AssistantAuditRow>(),
                rows<AssistantOutboxRow>(),
                get(),
                get(),
            )
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
        single<OutboxStore>(named("assistant.outbox")) { AssistantOutboxStore(rows<AssistantOutboxRow>()) }
        single(named("assistant.spec")) {
            KandraModuleSpec("circaso_assistant", assistantEntities, listOf(V001_CreateAssistantTables))
        }

        // domain
        single { AshaAgent(get(), get(), get()) }
        single { OrderedIds(clock) }
        single { TurnRecorder(get(), get(), get(), get(), get(), clock) }
        single { StartTurn(get(), get(), get(), get(), get(), get(), get(), clock) }
        single { ContinueTurn(get(), get(), get(), get()) }
        single { ListChat(get()) }
        single { DeleteChat(get()) }
        single { DeleteExchange(get(), get()) }
        single { GetUsage(get(), get(), get(), clock) }
        single { TranscribeVoice(get(), get(), get(), get(), clock) }
        single { SynthesizeSpeech(get(), get(), get(), get(), clock) }
        single { DeleteAssistantData(get()) }
        single { OpenLiveVoice(get(), get(), get(), get(), get(), get(), clock) }
        single { MeterLiveVoice(get(), clock) }

        // presentation
        single { ChatService(get(), get(), get(), get(), get()) }
        single { VoiceService(get(), get(), get()) }
        single { IdentityEventsService(get()) }
        single { LiveVoiceService(get(), get(), get(), get()) }
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
