package com.circaso.assistant.di

import com.circaso.assistant.data.blocks.JsonReplyBlocks
import com.circaso.assistant.data.cache.AesTextVault
import com.circaso.assistant.data.cache.RealtimeStatusFrames
import com.circaso.assistant.data.cache.RedisAshaHealth
import com.circaso.assistant.data.cache.RedisMascotScenes
import com.circaso.assistant.data.cache.RedisPendingTurns
import com.circaso.assistant.data.cache.RedisQuotaCounters
import com.circaso.assistant.data.cache.RedisVoiceSamples
import com.circaso.assistant.data.metrics.LoggedHealthLog
import com.circaso.assistant.data.metrics.LoggedVoiceFallbacks
import com.circaso.assistant.data.metrics.TelemetryLiveTimings
import com.circaso.assistant.data.repositories.AssistantExpiry
import com.circaso.assistant.data.repositories.AssistantStoreImpl
import com.circaso.assistant.data.repositories.ChatRepositoryImpl
import com.circaso.assistant.data.repositories.ConversationRepositoryImpl
import com.circaso.assistant.data.repositories.DatabaseTransactor
import com.circaso.assistant.data.repositories.EntitlementRepositoryImpl
import com.circaso.assistant.data.tables.AshaConversationsTable
import com.circaso.assistant.data.tables.AshaMessagesTable
import com.circaso.assistant.data.tables.AuditTable
import com.circaso.assistant.data.tables.EntitlementsTable
import com.circaso.assistant.data.tables.UsageDailyTable
import com.circaso.assistant.data.tools.AllAgentTools
import com.circaso.assistant.data.tools.CryptoTools
import com.circaso.assistant.data.tools.HttpAgentTools
import com.circaso.assistant.data.tools.MoneyTools
import com.circaso.assistant.data.tools.OwnTools
import com.circaso.assistant.domain.models.AshaVoice
import com.circaso.assistant.domain.models.Limits
import com.circaso.assistant.domain.models.VoiceGender
import com.circaso.assistant.domain.ports.AgentTools
import com.circaso.assistant.domain.ports.AshaHealth
import com.circaso.assistant.domain.ports.HealthLog
import com.circaso.assistant.domain.ports.LiveTimings
import com.circaso.assistant.domain.ports.MascotScenes
import com.circaso.assistant.domain.ports.PendingTurns
import com.circaso.assistant.domain.ports.QuotaCounters
import com.circaso.assistant.domain.ports.ReplyBlocks
import com.circaso.assistant.domain.ports.StatusFrames
import com.circaso.assistant.domain.ports.TextVault
import com.circaso.assistant.domain.ports.Transactor
import com.circaso.assistant.domain.ports.VoiceFallbacks
import com.circaso.assistant.domain.ports.VoiceSamples
import com.circaso.assistant.domain.repositories.AssistantStore
import com.circaso.assistant.domain.repositories.ChatRepository
import com.circaso.assistant.domain.repositories.ConversationRepository
import com.circaso.assistant.domain.repositories.EntitlementRepository
import com.circaso.assistant.domain.support.AshaAgent
import com.circaso.assistant.domain.support.AshaOutage
import com.circaso.assistant.domain.support.AshaVoices
import com.circaso.assistant.domain.support.AssistantConfig
import com.circaso.assistant.domain.support.ConversationResolver
import com.circaso.assistant.domain.support.ConversationTitler
import com.circaso.assistant.domain.support.DailyUsage
import com.circaso.assistant.domain.support.OrderedIds
import com.circaso.assistant.domain.support.SearchAllowance
import com.circaso.assistant.domain.support.TurnRecorder
import com.circaso.assistant.domain.support.UsageReader
import com.circaso.assistant.domain.support.WatchedLanguageModel
import com.circaso.assistant.domain.usecases.CheckLiveSession
import com.circaso.assistant.domain.usecases.ContinueTurn
import com.circaso.assistant.domain.usecases.CreateConversation
import com.circaso.assistant.domain.usecases.DeleteAssistantData
import com.circaso.assistant.domain.usecases.DeleteChat
import com.circaso.assistant.domain.usecases.DeleteConversation
import com.circaso.assistant.domain.usecases.DeleteExchange
import com.circaso.assistant.domain.usecases.GetAshaStatus
import com.circaso.assistant.domain.usecases.GetUsage
import com.circaso.assistant.domain.usecases.GetVoiceSample
import com.circaso.assistant.domain.usecases.ListChat
import com.circaso.assistant.domain.usecases.ListConversationMessages
import com.circaso.assistant.domain.usecases.ListConversations
import com.circaso.assistant.domain.usecases.ListVoices
import com.circaso.assistant.domain.usecases.MeterLiveVoice
import com.circaso.assistant.domain.usecases.MonitorAshaHealth
import com.circaso.assistant.domain.usecases.OpenLiveVoice
import com.circaso.assistant.domain.usecases.RecordLiveTiming
import com.circaso.assistant.domain.usecases.RenameConversation
import com.circaso.assistant.domain.usecases.SaveLiveVoiceUsage
import com.circaso.assistant.domain.usecases.StartTurn
import com.circaso.assistant.domain.usecases.SynthesizeSpeech
import com.circaso.assistant.domain.usecases.TranscribeVoice
import com.circaso.assistant.presentation.controllers.chatController
import com.circaso.assistant.presentation.controllers.conversationController
import com.circaso.assistant.presentation.controllers.liveVoiceController
import com.circaso.assistant.presentation.controllers.statusController
import com.circaso.assistant.presentation.controllers.voiceController
import com.circaso.assistant.presentation.events.IdentityEventHandler
import com.circaso.assistant.presentation.services.ChatService
import com.circaso.assistant.presentation.services.ConversationService
import com.circaso.assistant.presentation.services.IdentityEventsService
import com.circaso.assistant.presentation.services.LiveVoiceService
import com.circaso.assistant.presentation.services.StatusService
import com.circaso.assistant.presentation.services.VoiceService
import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.api.Databases
import com.circaso.infrastructure.api.HttpClients
import com.circaso.infrastructure.api.RedisScopes
import com.circaso.infrastructure.api.ScheduledJob
import com.circaso.infrastructure.api.Secrets
import com.circaso.integrations.api.LanguageModel
import com.circaso.integrations.api.WebSearch
import com.circaso.observability.api.Telemetry
import com.circaso.shared.EventSubscription
import com.circaso.shared.Resource
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
        tables = listOf(AshaConversationsTable, AshaMessagesTable, UsageDailyTable, EntitlementsTable, AuditTable),
        migrationsLocation = "classpath:db/assistant",
        outbox = true,
    )

/**
 * Asha's limits from the environment (decision 2026-10-31): `ASSISTANT_LIMITS_UNLIMITED` lifts them all (testing; off
 * by default), and each plan's numbers can be set on their own. Unset, the defaults in [AssistantConfig] hold.
 */
internal fun assistantConfig(assistant: Config): AssistantConfig {
    val defaults = AssistantConfig()

    fun limits(plan: String, base: Limits) =
        Limits(
            textTurnsPerDay = assistant.int("$plan.text-turns-per-day", base.textTurnsPerDay),
            voiceSecondsPerDay = assistant.int("$plan.voice-seconds-per-day", base.voiceSecondsPerDay),
            contextMessages = assistant.int("$plan.context-messages", base.contextMessages),
            webSearchesPerDay = assistant.int("$plan.web-searches-per-day", base.webSearchesPerDay),
        )
    return defaults.copy(
        free = limits("free", defaults.free),
        plus = limits("plus", defaults.plus),
        isUnlimited = assistant.boolean("limits-unlimited", false),
        maxToolSteps = assistant.int("max-tool-steps", defaults.maxToolSteps),
        maxWebSearchesPerTurn = assistant.int("max-web-searches-per-turn", defaults.maxWebSearchesPerTurn),
        voices = assistantVoices(assistant, defaults.voices),
    )
}

/**
 * Asha's voices from the environment (decision 2026-10-31). `ASSISTANT_VOICES` replaces the whole list, as
 * `id:Name:Character:gender` entries separated by commas (`luna:Luna:Friendly:female,zeus:Zeus:Steady:male`); the id is
 * the voice provider's speaker name. `ASSISTANT_DEFAULT_VOICE` picks the default, else the list's first voice when the
 * list is set, else Luna. A malformed list fails at start, not on a user's request.
 */
internal fun assistantVoices(assistant: Config, defaults: AshaVoices): AshaVoices {
    val listed = assistant.string("voices")?.takeIf { it.isNotBlank() }
    val voices = listed?.split(',')?.map { voiceOf(it.trim()) } ?: defaults.all
    val defaultId = assistant.string("default-voice")?.trim()?.takeIf { it.isNotEmpty() }
    return AshaVoices(voices, defaultId ?: if (listed != null) voices.first().id else defaults.default.id)
}

private fun voiceOf(entry: String): AshaVoice {
    val parts = entry.split(':').map { it.trim() }
    check(parts.size == VOICE_FIELDS && parts.none { it.isEmpty() }) {
        "ASSISTANT_VOICES entry '$entry' must be id:Name:Character:gender"
    }
    val gender =
        VoiceGender.entries.firstOrNull { it.name.equals(parts[3], ignoreCase = true) }
            ?: error("ASSISTANT_VOICES entry '$entry' has gender '${parts[3]}' (female or male)")
    return AshaVoice(parts[0].lowercase(), parts[1], parts[2], gender)
}

private const val VOICE_FIELDS = 4

/**
 * How often `assistant.model-health` looks at Asha's health (decision 2026-11-07): a Redis read while she is up, and
 * a probe only when one is due while she is down (30 s apart at first, 2 min at most).
 */
private val HEALTH_INTERVAL: Duration = Duration.ofSeconds(10)

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
        val assistantConfig = assistantConfig(assistant)

        single { assistantConfig }

        // data
        single(named("assistant.spec")) { assistantDatabaseSpec }
        single(named("assistant.database")) { get<Databases>().scope(assistantDatabaseSpec.schema) }
        single<Transactor> { DatabaseTransactor(get(named("assistant.database"))) }
        single<TextVault> { AesTextVault(get<Secrets>(), local) }
        single<QuotaCounters> { RedisQuotaCounters(get<RedisScopes>().scope("ast")) }
        single<PendingTurns> { RedisPendingTurns(get<RedisScopes>().scope("ast")) }
        single<MascotScenes> { RedisMascotScenes(get<RedisScopes>().scope("ast")) }
        single<LiveTimings> { TelemetryLiveTimings(get<Telemetry>()) }
        single<VoiceFallbacks> { LoggedVoiceFallbacks() }
        single<VoiceSamples> { RedisVoiceSamples(get<RedisScopes>().scope("ast")) }
        single<AshaHealth> { RedisAshaHealth(get<RedisScopes>().scope("ast")) }
        single<StatusFrames> { RealtimeStatusFrames(get(), clock) }
        single<HealthLog> { LoggedHealthLog() }
        single<ChatRepository> { ChatRepositoryImpl(get(named("assistant.database")), get()) }
        single<ConversationRepository> { ConversationRepositoryImpl(get(named("assistant.database")), get()) }
        single<EntitlementRepository> { EntitlementRepositoryImpl(get(named("assistant.database")), clock) }
        single<AssistantStore> { AssistantStoreImpl(get(named("assistant.database")), get(), clock) }
        single(named("assistant.purge-expired")) {
            val expiry = AssistantExpiry(get(named("assistant.database")))
            ScheduledJob("assistant.purge-expired", PURGE_INTERVAL) { expiry.run() }
        }
        single(named("assistant.model-health")) {
            val status = get<StatusService>()
            ScheduledJob("assistant.model-health", HEALTH_INTERVAL) {
                (status.monitor() as? Resource.Success)?.data ?: 0
            }
        }
        single<ReplyBlocks> { JsonReplyBlocks() }
        single<AgentTools> {
            val routes =
                HttpAgentTools(
                    get<HttpClients>().named("agent"),
                    assistant
                        .string(
                            "agent-base-url",
                            "http://localhost:" + config.string("port", "8080"),
                        ).trimEnd('/'),
                )
            // Money maths (decision 2026-11-07): rates, conversions and sums through tools, never the model's own.
            AllAgentTools(
                listOf(
                    routes,
                    OwnTools(get<WebSearch>(), get(), get()),
                    CryptoTools(get(), get()),
                    MoneyTools(get(), get(), get(), clock),
                ),
            )
        }

        // domain
        // Asha's model, watched for her health (decision 2026-11-07); the monitor probes the model itself
        single {
            AshaAgent(
                WatchedLanguageModel(get<LanguageModel>(), get(), clock, get()),
                get(),
                get(),
                get(),
                get(),
            )
        }
        single { AshaOutage(get(), get()) }
        single { UsageReader(get(), get(), get(), clock) }
        single { SearchAllowance(get(), get(), get(), clock) }
        single { OrderedIds(clock) }
        single { DailyUsage(get(), get(), get(), clock) }
        single { ConversationResolver(get(), get(), clock) }
        single { ConversationTitler(get(), get()) }
        single { TurnRecorder(get(), get(), get(), get(), get(), get(), get(), get(), clock) }
        single { StartTurn(get(), get(), get(), get(), get(), get(), get(), get(), get(), clock, get()) }
        single { ContinueTurn(get(), get(), get(), get(), get(), get(), clock, get()) }
        single { GetAshaStatus(get()) }
        single { MonitorAshaHealth(get(), get<LanguageModel>(), get(), clock, get()) }
        single { ListChat(get()) }
        single { ListConversations(get(), get()) }
        single { CreateConversation(get()) }
        single { RenameConversation(get(), clock) }
        single { DeleteConversation(get(), get(), get()) }
        single { ListConversationMessages(get(), get()) }
        single { DeleteChat(get(), get()) }
        single { DeleteExchange(get(), get(), get()) }
        single { GetUsage(get()) }
        single { TranscribeVoice(get(), get(), get(), get(), get(), clock) }
        single { SynthesizeSpeech(get(), get(), get(), get(), get(), get(), clock) }
        single { ListVoices(get()) }
        single { GetVoiceSample(get(), get(), get()) }
        single { DeleteAssistantData(get(), get(), get(), get(), get(), clock) }
        single { OpenLiveVoice(get(), get(), get(), get(), get(), get(), get(), clock) }
        single { MeterLiveVoice(get(), clock) }
        single { SaveLiveVoiceUsage(get()) }
        single { CheckLiveSession(get()) }
        single { RecordLiveTiming(get()) }

        // presentation
        single { ChatService(get(), get(), get(), get(), get()) }
        single { ConversationService(get(), get(), get(), get(), get()) }
        single { VoiceService(get(), get(), get(), get(), get()) }
        single { IdentityEventsService(get()) }
        single { LiveVoiceService(get(), get(), get(), get(), get(), get(), get()) }
        single { StatusService(get(), get()) }
        single<RouteContributor>(named("assistant.routes")) {
            val chat = get<ChatService>()
            val conversations = get<ConversationService>()
            val voice = get<VoiceService>()
            val live = get<LiveVoiceService>()
            val status = get<StatusService>()
            RouteContributor { route ->
                route.chatController(chat)
                route.conversationController(conversations)
                route.voiceController(voice)
                route.liveVoiceController(live)
                route.statusController(status)
            }
        }
        single<EventSubscription>(named("assistant.identity-events")) { IdentityEventHandler(get()).subscription() }
    }
