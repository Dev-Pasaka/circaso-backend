package com.circaso.assistant

import com.circaso.assistant.data.blocks.JsonReplyBlocks
import com.circaso.assistant.domain.models.AshaStatus
import com.circaso.assistant.domain.models.AshaVoice
import com.circaso.assistant.domain.models.ChatMessage
import com.circaso.assistant.domain.models.Conversation
import com.circaso.assistant.domain.models.ConversationCursor
import com.circaso.assistant.domain.models.Limits
import com.circaso.assistant.domain.models.MascotSceneState
import com.circaso.assistant.domain.models.Plan
import com.circaso.assistant.domain.models.ProbeState
import com.circaso.assistant.domain.models.TokenCounts
import com.circaso.assistant.domain.ports.AgentTools
import com.circaso.assistant.domain.ports.AshaHealth
import com.circaso.assistant.domain.ports.MascotScenes
import com.circaso.assistant.domain.ports.PendingTurn
import com.circaso.assistant.domain.ports.PendingTurns
import com.circaso.assistant.domain.ports.QuotaCounters
import com.circaso.assistant.domain.ports.StatusFrames
import com.circaso.assistant.domain.ports.ToolContext
import com.circaso.assistant.domain.ports.Transactor
import com.circaso.assistant.domain.ports.VoiceFallbacks
import com.circaso.assistant.domain.ports.VoiceSamples
import com.circaso.assistant.domain.repositories.AssistantChanges
import com.circaso.assistant.domain.repositories.AssistantStore
import com.circaso.assistant.domain.repositories.AuditEntry
import com.circaso.assistant.domain.repositories.ChatRepository
import com.circaso.assistant.domain.repositories.ConversationRepository
import com.circaso.assistant.domain.repositories.EntitlementRepository
import com.circaso.assistant.domain.support.AshaAgent
import com.circaso.assistant.domain.support.AshaOutage
import com.circaso.assistant.domain.support.AshaPrompt
import com.circaso.assistant.domain.support.AssistantConfig
import com.circaso.assistant.domain.support.ConversationResolver
import com.circaso.assistant.domain.support.ConversationTitler
import com.circaso.assistant.domain.support.DailyUsage
import com.circaso.assistant.domain.support.OrderedIds
import com.circaso.assistant.domain.support.TurnRecorder
import com.circaso.assistant.domain.support.UsageReader
import com.circaso.assistant.domain.support.WatchedLanguageModel
import com.circaso.assistant.domain.usecases.ContinueTurn
import com.circaso.assistant.domain.usecases.CreateConversation
import com.circaso.assistant.domain.usecases.DeleteAssistantData
import com.circaso.assistant.domain.usecases.DeleteChat
import com.circaso.assistant.domain.usecases.DeleteConversation
import com.circaso.assistant.domain.usecases.DeleteExchange
import com.circaso.assistant.domain.usecases.GetUsage
import com.circaso.assistant.domain.usecases.GetVoiceSample
import com.circaso.assistant.domain.usecases.ListChat
import com.circaso.assistant.domain.usecases.ListConversationMessages
import com.circaso.assistant.domain.usecases.ListConversations
import com.circaso.assistant.domain.usecases.ListVoices
import com.circaso.assistant.domain.usecases.RenameConversation
import com.circaso.assistant.domain.usecases.StartTurn
import com.circaso.assistant.domain.usecases.SynthesizeSpeech
import com.circaso.assistant.domain.usecases.TranscribeVoice
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.LanguageModel
import com.circaso.integrations.api.ModelRequest
import com.circaso.integrations.api.ModelResponse
import com.circaso.integrations.api.ModelStream
import com.circaso.integrations.api.ModelStreamEvent
import com.circaso.integrations.api.ModelUsage
import com.circaso.integrations.api.SpeechToText
import com.circaso.integrations.api.SpokenAudio
import com.circaso.integrations.api.TextToSpeech
import com.circaso.integrations.api.ToolCall
import com.circaso.integrations.api.ToolSpec
import com.circaso.integrations.api.Transcript
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import kotlinx.coroutines.CompletableDeferred
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One in-memory stand-in for the repository ports and the transaction boundary. A `run` that throws puts the chat and
 * the audit trail back as they were, as a rolled-back transaction would.
 */
class MemoryAssistant :
    ChatRepository,
    ConversationRepository,
    AssistantStore,
    Transactor {
    val messages = CopyOnWriteArrayList<ChatMessage>()
    val conversations = CopyOnWriteArrayList<Conversation>()
    val audit = CopyOnWriteArrayList<AuditEntry>()
    val usage = ConcurrentHashMap<String, List<Number>>()

    /** `userId:day:model` → the tokens stored for it (the per-model row). */
    val modelTokens = ConcurrentHashMap<String, TokenCounts>()
    val erased = CopyOnWriteArrayList<UserId>()

    override suspend fun <T> run(operation: String, block: suspend () -> T): T {
        val savedMessages = messages.toList()
        val savedConversations = conversations.toList()
        val savedAudit = audit.toList()
        try {
            return block()
        } catch (e: Exception) {
            messages.clear()
            messages += savedMessages
            conversations.clear()
            conversations += savedConversations
            audit.clear()
            audit += savedAudit
            throw e
        }
    }

    override suspend fun <T> attempt(operation: String, block: suspend () -> Resource<T>): Resource<T> =
        try {
            run(operation) { block().also { if (it is Resource.Error) throw Failed(it) } }
        } catch (e: Failed) {
            e.error
        }

    /** Carries an `attempt` block's error out of [run], so [run] puts everything back. */
    private class Failed(val error: Resource.Error) : RuntimeException()

    override suspend fun delete(userId: UserId, messageIds: Collection<UUID>): Int {
        val gone = messages.filter { it.userId == userId && it.messageId in messageIds }
        messages.removeAll(gone.toSet())
        return gone.size
    }

    /** Makes the next [erase] fail, as a database that is down would. */
    var eraseFails = false

    override suspend fun erase(userId: UserId) {
        if (eraseFails) error("the database is down")
        deleteChat(userId)
        erased += userId
    }

    override suspend fun find(userId: UserId, messageId: UUID) =
        messages.firstOrNull {
            it.userId == userId &&
                it.messageId == messageId
        }

    override suspend fun list(
        userId: UserId,
        before: UUID?,
        limit: Int,
    ) = messages
        .filter {
            it.userId == userId && (before == null || it.messageId < before)
        }.sortedByDescending { it.messageId }
        .take(limit)

    override suspend fun listIn(
        userId: UserId,
        conversationId: UUID,
        before: UUID?,
        limit: Int,
    ) = list(userId, before, Int.MAX_VALUE).filter { it.conversationId == conversationId }.take(limit)

    override suspend fun oldestIn(userId: UserId, conversationId: UUID) =
        messages
            .filter { it.userId == userId && it.conversationId == conversationId }
            .minByOrNull { it.messageId }

    override suspend fun conversation(userId: UserId, conversationId: UUID) =
        conversations.firstOrNull { it.userId == userId && it.conversationId == conversationId }

    override suspend fun latestConversation(userId: UserId) = conversations(userId, null, 1).firstOrNull()

    override suspend fun conversations(
        userId: UserId,
        after: ConversationCursor?,
        limit: Int,
    ) = conversations
        .filter { it.userId == userId }
        .sortedWith(compareByDescending<Conversation> { it.lastMessageAt }.thenByDescending { it.conversationId })
        .filter {
            after == null ||
                it.lastMessageAt < after.lastMessageAt ||
                (it.lastMessageAt == after.lastMessageAt && it.conversationId < after.conversationId)
        }.take(limit)

    override suspend fun addConversation(conversation: Conversation) {
        conversations += conversation
    }

    private fun replace(
        userId: UserId,
        conversationId: UUID,
        change: (Conversation) -> Conversation,
    ): Conversation? =
        synchronized(conversations) {
            val i = conversations.indexOfFirst { it.userId == userId && it.conversationId == conversationId }
            if (i < 0) return null
            change(conversations[i]).also { conversations[i] = it }
        }

    override suspend fun renameConversation(
        userId: UserId,
        conversationId: UUID,
        title: String,
        at: Instant,
    ) = replace(userId, conversationId) { it.copy(title = title, updatedAt = at) }

    override suspend fun touchConversation(
        userId: UserId,
        conversationId: UUID,
        at: Instant,
    ) = replace(userId, conversationId) { it.copy(updatedAt = at, lastMessageAt = at) }

    override suspend fun titleConversation(
        userId: UserId,
        conversationId: UUID,
        title: String,
    ): Boolean {
        var titled = false
        replace(userId, conversationId) {
            if (it.title == null) {
                titled = true
                it.copy(title = title)
            } else {
                it
            }
        }
        return titled
    }

    override suspend fun deleteConversation(userId: UserId, conversationId: UUID): Int {
        val gone = conversations.filter { it.userId == userId && it.conversationId == conversationId }
        conversations.removeAll(gone.toSet())
        if (gone.isNotEmpty()) messages.removeAll { it.userId == userId && it.conversationId == conversationId }
        return gone.size
    }

    override suspend fun commit(changes: AssistantChanges) {
        messages += changes.messages
        audit += changes.audit
    }

    override suspend fun deleteChat(userId: UserId): Int {
        val gone = messages.filter { it.userId == userId }
        val goneConversations = conversations.filter { it.userId == userId }
        messages.removeAll(gone.toSet())
        conversations.removeAll(goneConversations.toSet())
        return gone.size + goneConversations.size
    }

    override suspend fun saveUsage(
        userId: UserId,
        day: String,
        textTurns: Int,
        voiceSeconds: Int,
        inputTokens: Long,
        outputTokens: Long,
        at: Instant,
        webSearches: Int,
    ) {
        usage["$userId:$day"] = listOf(textTurns, voiceSeconds, inputTokens, outputTokens, webSearches)
    }

    override suspend fun saveModelTokens(
        userId: UserId,
        day: String,
        byModel: Map<String, TokenCounts>,
        at: Instant,
    ) {
        byModel.forEach { (model, tokens) -> modelTokens["$userId:$day:$model"] = tokens }
    }
}

class MemoryQuota : QuotaCounters {
    private val counters = ConcurrentHashMap<String, Long>()

    private fun add(key: String, by: Long) = counters.merge(key, by, Long::plus)!!

    override suspend fun countTextTurn(
        userId: UserId,
        day: String,
        cap: Int,
    ): Long =
        synchronized(counters) {
            val after = (counters["t:$userId:$day"] ?: 0) + 1
            if (after <= cap) counters["t:$userId:$day"] = after
            after
        }

    override suspend fun textTurns(userId: UserId, day: String) = counters["t:$userId:$day"] ?: 0

    override suspend fun addVoiceSeconds(
        userId: UserId,
        day: String,
        seconds: Int,
    ) = add(
        "v:$userId:$day",
        seconds.toLong(),
    )

    override suspend fun voiceSeconds(userId: UserId, day: String) = counters["v:$userId:$day"] ?: 0

    override suspend fun addVoiceSecondsUpTo(
        userId: UserId,
        day: String,
        seconds: Int,
        cap: Int,
    ): Long =
        synchronized(counters) {
            val now = counters["v:$userId:$day"] ?: 0
            (now + seconds.coerceAtMost((cap - now).toInt()).coerceAtLeast(0)).also { counters["v:$userId:$day"] = it }
        }

    override suspend fun addTokens(
        userId: UserId,
        day: String,
        input: Int,
        output: Int,
    ) {
        add("i:$userId:$day", input.toLong())
        add("o:$userId:$day", output.toLong())
    }

    private val byModel = ConcurrentHashMap<String, TokenCounts>()

    override suspend fun addModelTokens(
        userId: UserId,
        day: String,
        byModel: Map<String, TokenCounts>,
    ) {
        byModel.forEach { (model, tokens) -> this.byModel.merge("$userId:$day|$model", tokens, TokenCounts::plus) }
    }

    override suspend fun modelTokens(userId: UserId, day: String): Map<String, TokenCounts> =
        byModel.filterKeys { it.startsWith("$userId:$day|") }.mapKeys { it.key.substringAfter('|') }

    override suspend fun tokens(userId: UserId, day: String) =
        (counters["i:$userId:$day"] ?: 0) to (counters["o:$userId:$day"] ?: 0)

    override suspend fun countWebSearch(
        userId: UserId,
        day: String,
        cap: Int,
    ): Long =
        synchronized(counters) {
            val after = (counters["s:$userId:$day"] ?: 0) + 1
            if (after <= cap) counters["s:$userId:$day"] = after
            after
        }

    override suspend fun webSearches(userId: UserId, day: String) = counters["s:$userId:$day"] ?: 0

    override suspend fun forget(userId: UserId, days: List<String>) {
        counters.keys.removeAll { key -> days.any { day -> key.endsWith(":$userId:$day") } }
    }
}

class MemoryPending : PendingTurns {
    private val turns = ConcurrentHashMap<Pair<UserId, UUID>, PendingTurn>()

    override suspend fun save(turn: PendingTurn) {
        turns[turn.userId to turn.turnId] = turn
    }

    override suspend fun callIdOf(userId: UserId, turnId: UUID) = turns[userId to turnId]?.callId

    override suspend fun take(userId: UserId, turnId: UUID) = turns.remove(userId to turnId)

    override suspend fun forget(userId: UserId) {
        turns.keys.removeAll { it.first == userId }
    }

    fun parked(userId: UserId) = turns.keys.count { it.first == userId }
}

class Plans(var plan: Plan = Plan.FREE) : EntitlementRepository {
    override suspend fun planOf(userId: UserId) = plan
}

class FakeTools : AgentTools {
    data class Called(
        val name: String,
        val arguments: String,
        val token: String?,
        val currency: String? = null,
    )

    val calls = CopyOnWriteArrayList<Called>()
    var answer: (
        String,
    ) -> Resource<String> = { Resource.success("""{"contacts":[{"userId":"u1","displayName":"Mum Wanjiku"}]}""") }

    override fun specs(hasAgentToken: Boolean): List<ToolSpec> =
        if (hasAgentToken) listOf(AshaPrompt.searchContacts, AshaPrompt.listContacts) else emptyList()

    override fun handles(toolName: String) = toolName in setOf("searchContacts", "listContacts")

    override fun label(toolName: String, argumentsJson: String) = "Looking at your contacts…"

    override suspend fun call(
        toolName: String,
        argumentsJson: String,
        context: ToolContext,
    ): Resource<String> {
        if (context.agentToken == null) return Resource.error(IntegrationError.Rejected("no agent token"))
        calls += Called(toolName, argumentsJson, context.agentToken, context.currency)
        return answer(toolName)
    }
}

/** A streamed answer played from a list: each entry makes the next event (and may wait first). */
class ScriptedStream(private val events: List<suspend () -> ModelStreamEvent>) : ModelStream {
    private var next = 0

    @Volatile var isClosed = false

    override suspend fun next(): ModelStreamEvent? = if (isClosed || next >= events.size) null else events[next++]()

    override suspend fun close() {
        isClosed = true
    }
}

/** A model that follows a script and records what it was asked. Asha's turns stream; [complete] reads a stream whole. */
class ScriptedModel : LanguageModel {
    val requests = CopyOnWriteArrayList<ModelRequest>()
    val streams = CopyOnWriteArrayList<ScriptedStream>()
    private val steps = ArrayDeque<suspend (ModelRequest) -> Resource<ModelStream>>()

    /** A whole answer, streamed as the contract's default does: its text in one piece, then done. */
    fun then(step: (ModelRequest) -> Resource<ModelResponse>) =
        apply {
            steps.add { request ->
                when (val answer = step(request)) {
                    is Resource.Error -> answer
                    is Resource.Success -> Resource.success(whole(answer.data))
                }
            }
        }

    /**
     * An answer written in [pieces]; after the first piece it waits for [gate], if given. It ends with [end], by
     * default the whole answer (the pieces joined, and [toolCalls]).
     */
    fun streamed(
        vararg pieces: String,
        toolCalls: List<ToolCall> = emptyList(),
        gate: CompletableDeferred<Unit>? = null,
        end: ModelStreamEvent? = null,
    ) = apply {
        val text = pieces.joinToString("").takeIf { it.isNotEmpty() }
        val last = end ?: ModelStreamEvent.Done(ModelResponse(text, toolCalls, ModelUsage(100, 20)))
        val events =
            pieces.mapIndexed { i, piece ->
                suspend {
                    if (i == 1) gate?.await()
                    ModelStreamEvent.Text(piece)
                }
            } + listOf(suspend { last })
        steps.add { Resource.success(ScriptedStream(events).also { streams += it }) }
    }

    private fun whole(response: ModelResponse): ModelStream {
        val text = response.text?.takeIf { it.isNotEmpty() }?.let { listOf(suspend { ModelStreamEvent.Text(it) }) }
        return ScriptedStream(text.orEmpty() + listOf(suspend { ModelStreamEvent.Done(response) }))
    }

    fun say(text: String) = then { Resource.success(ModelResponse(text, emptyList(), ModelUsage(100, 20))) }

    fun call(
        name: String,
        arguments: String = "{}",
        id: String = "call-$name",
    ) = then { Resource.success(ModelResponse(null, listOf(ToolCall(id, name, arguments)), ModelUsage(100, 10))) }

    fun fail() = then { Resource.error(IntegrationError.Unavailable("model")) }

    /** The model refuses this request: not an outage, so Asha stays available. */
    fun reject() = then { Resource.error(IntegrationError.Rejected("The model declined to answer.")) }

    /** Answers to [probe], in order; an empty list is a probe that answers. */
    val probes = java.util.ArrayDeque<Resource<Unit>>()
    var probed = 0

    override suspend fun probe(): Resource<Unit> {
        probed++
        return probes.pollFirst() ?: Resource.success(Unit)
    }

    override suspend fun stream(request: ModelRequest): Resource<ModelStream> {
        requests += request
        return steps.pollFirst()?.invoke(request)
            ?: Resource.success(whole(ModelResponse("(unscripted)", emptyList(), ModelUsage(1, 1))))
    }

    override suspend fun complete(request: ModelRequest): Resource<ModelResponse> {
        val stream =
            when (val opened = stream(request)) {
                is Resource.Error -> return opened
                is Resource.Success -> opened.data
            }
        while (true) {
            when (val event = stream.next() ?: return Resource.error(IntegrationError.Unavailable("model"))) {
                is ModelStreamEvent.Done -> return Resource.success(event.response)
                is ModelStreamEvent.Failed -> return Resource.error(event.error)
                else -> continue
            }
        }
    }
}

class RecordingSpeech :
    SpeechToText,
    TextToSpeech {
    var seconds = 5

    override suspend fun transcribe(
        audio: ByteArray,
        contentType: String,
        language: String?,
    ) = Resource.success(
        Transcript(String(audio), seconds),
    )

    /** Languages this voice refuses, like Aura refusing Swahili. */
    var unspoken: Set<String> = emptySet()

    /** The voice of every clip asked for, in order. */
    val voices = CopyOnWriteArrayList<String?>()

    /** When false, every clip fails as the provider being down. */
    @Volatile var isUp = true

    override suspend fun speak(
        text: String,
        language: String?,
        voice: String?,
    ): Resource<SpokenAudio> {
        voices += voice
        if (!isUp) return Resource.error(IntegrationError.Unavailable("voice"))
        if (language in unspoken) return Resource.error(IntegrationError.UnsupportedLanguage(language.orEmpty()))
        return Resource.success(SpokenAudio(text.toByteArray(), "audio/mpeg"))
    }
}

/** Voice previews kept in memory, as Redis keeps them for a day. */
class MemorySamples : VoiceSamples {
    val kept = ConcurrentHashMap<String, SpokenAudio>()

    override suspend fun find(voiceId: String, text: String): SpokenAudio? = kept["$voiceId:$text"]

    override suspend fun save(
        voiceId: String,
        text: String,
        audio: SpokenAudio,
    ) {
        kept["$voiceId:$text"] = audio
    }
}

/** The mascot's scenes kept in memory, as Redis keeps them for 30 days. */
class MemoryScenes : MascotScenes {
    val kept = ConcurrentHashMap<Pair<UserId, UUID>, MascotSceneState>()

    override suspend fun find(userId: UserId, conversationId: UUID): MascotSceneState? = kept[userId to conversationId]

    override suspend fun save(
        userId: UserId,
        conversationId: UUID,
        state: MascotSceneState,
    ) {
        kept[userId to conversationId] = state
    }

    override suspend fun forget(userId: UserId) {
        kept.keys.removeIf { it.first == userId }
    }
}

/** The fallbacks reported, as (asked for, used). */
class RecordingFallbacks : VoiceFallbacks {
    val seen = CopyOnWriteArrayList<Pair<String?, String>>()

    override fun fellBack(requested: String?, used: AshaVoice) {
        seen += requested to used.id
    }
}

/** Asha's health in memory, as Redis keeps it for every node. */
class MemoryHealth : AshaHealth {
    private val lock = Any()

    @Volatile var down: Instant? = null

    @Volatile var up: Instant? = null
    val affected =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<UserId>()
    val notices = java.util.concurrent.ConcurrentHashMap<UserId, Instant>()

    @Volatile var probeState: ProbeState? = null
    var now: () -> Instant = { Instant.now() }

    override suspend fun status() = down?.let { AshaStatus(false, it) } ?: AshaStatus(true, up)

    override suspend fun markDown(at: Instant): Boolean =
        synchronized(lock) {
            if (down != null) return@synchronized false
            down = at
            probeState = null
            true
        }

    override suspend fun markUp(at: Instant): Boolean =
        synchronized(lock) {
            if (down == null) return@synchronized false
            down = null
            up = at
            probeState = null
            true
        }

    override suspend fun recordAffected(userId: UserId) {
        affected += userId
    }

    override suspend fun takeAffected(): List<UserId> =
        synchronized(lock) { affected.toList().also { affected.clear() } }

    override suspend fun claimNotice(userId: UserId, window: Duration): Boolean =
        synchronized(lock) {
            val last = notices[userId]
            if (last != null && last.plus(window).isAfter(now())) return@synchronized false
            notices[userId] = now()
            true
        }

    override suspend fun probe() = probeState

    override suspend fun saveProbe(state: ProbeState) {
        probeState = state
    }
}

/** The status frames sent, as (users, status). */
class RecordingStatusFrames : StatusFrames {
    val sent = CopyOnWriteArrayList<Pair<List<UserId>, AshaStatus>>()

    override suspend fun send(userIds: Collection<UserId>, status: AshaStatus) {
        sent += userIds.toList() to status
    }
}

class Harness(config: AssistantConfig = AssistantConfig(), val owner: UserId = UserId.new()) {
    /** Knows [owner]'s tokens (see [TestVerifier]); A2, A9 and A11 check the agent token with it. */
    val verifier = TestVerifier(owner)
    val memory = MemoryAssistant()
    val quota = MemoryQuota()
    val pending = MemoryPending()
    val plans = Plans()
    val tools = FakeTools()
    val model = ScriptedModel()
    val speech = RecordingSpeech()
    val samples = MemorySamples()
    val fallbacks = RecordingFallbacks()
    val scenes = MemoryScenes()
    private val clock = Clock.systemUTC()
    val config = config

    val health = MemoryHealth()
    val outage = AshaOutage(health)
    private val agent = AshaAgent(WatchedLanguageModel(model, health, clock), tools, config, JsonReplyBlocks(), scenes)
    private val ids = OrderedIds(clock)
    val resolver = ConversationResolver(memory, ids, clock)
    private val titler = ConversationTitler(memory, memory)
    private val recorder = TurnRecorder(memory, memory, titler, pending, quota, config, ids, memory, clock)
    val dailyUsage = DailyUsage(quota, memory, memory, clock)

    val start = StartTurn(verifier, memory, resolver, plans, quota, agent, recorder, config, ids, clock, outage)
    val resume = ContinueTurn(verifier, pending, resolver, agent, recorder, config, clock, outage)

    /** A9 over another store of waiting turns, for races. */
    fun resumeWith(turns: PendingTurns) =
        ContinueTurn(verifier, turns, resolver, agent, recorder, config, clock, outage)

    val list = ListChat(memory)
    val listConversations = ListConversations(memory, titler)
    val createConversation = CreateConversation(resolver)
    val renameConversation = RenameConversation(memory, clock)
    val deleteConversation = DeleteConversation(memory, memory, memory)
    val conversationMessages = ListConversationMessages(memory, memory)
    val deleteAll = DeleteChat(memory, memory)
    val deleteOne = DeleteExchange(memory, memory, memory)
    val usage = GetUsage(UsageReader(plans, quota, config, clock))
    val transcribe = TranscribeVoice(speech, plans, quota, dailyUsage, config, clock)
    val speak = SynthesizeSpeech(speech, plans, quota, dailyUsage, config, fallbacks, clock)
    val listVoices = ListVoices(config)
    val voiceSample = GetVoiceSample(speech, samples, config)
    val deleteData = DeleteAssistantData(memory, memory, pending, quota, scenes, clock)
}

fun smallLimits(turns: Int = 2, context: Int = 4) = AssistantConfig(free = Limits(turns, 30, context))
