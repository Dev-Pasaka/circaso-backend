package com.circaso.assistant

import com.circaso.assistant.data.blocks.JsonReplyBlocks
import com.circaso.assistant.domain.models.ChatMessage
import com.circaso.assistant.domain.models.Limits
import com.circaso.assistant.domain.models.Plan
import com.circaso.assistant.domain.ports.AgentTools
import com.circaso.assistant.domain.ports.PendingTurn
import com.circaso.assistant.domain.ports.PendingTurns
import com.circaso.assistant.domain.ports.QuotaCounters
import com.circaso.assistant.domain.ports.ToolContext
import com.circaso.assistant.domain.ports.Transactor
import com.circaso.assistant.domain.repositories.AssistantChanges
import com.circaso.assistant.domain.repositories.AssistantStore
import com.circaso.assistant.domain.repositories.AuditEntry
import com.circaso.assistant.domain.repositories.ChatRepository
import com.circaso.assistant.domain.repositories.EntitlementRepository
import com.circaso.assistant.domain.support.AshaAgent
import com.circaso.assistant.domain.support.AshaPrompt
import com.circaso.assistant.domain.support.AssistantConfig
import com.circaso.assistant.domain.support.DailyUsage
import com.circaso.assistant.domain.support.OrderedIds
import com.circaso.assistant.domain.support.TurnRecorder
import com.circaso.assistant.domain.support.UsageReader
import com.circaso.assistant.domain.usecases.ContinueTurn
import com.circaso.assistant.domain.usecases.DeleteAssistantData
import com.circaso.assistant.domain.usecases.DeleteChat
import com.circaso.assistant.domain.usecases.DeleteExchange
import com.circaso.assistant.domain.usecases.GetUsage
import com.circaso.assistant.domain.usecases.ListChat
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
    AssistantStore,
    Transactor {
    val messages = CopyOnWriteArrayList<ChatMessage>()
    val audit = CopyOnWriteArrayList<AuditEntry>()
    val usage = ConcurrentHashMap<String, List<Number>>()
    val erased = CopyOnWriteArrayList<UserId>()

    override suspend fun <T> run(operation: String, block: suspend () -> T): T {
        val savedMessages = messages.toList()
        val savedAudit = audit.toList()
        try {
            return block()
        } catch (e: Exception) {
            messages.clear()
            messages += savedMessages
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

    override suspend fun commit(changes: AssistantChanges) {
        messages += changes.messages
        audit += changes.audit
    }

    override suspend fun deleteChat(userId: UserId): Int {
        val gone = messages.filter { it.userId == userId }
        messages.removeAll(gone.toSet())
        return gone.size
    }

    override suspend fun saveUsage(
        userId: UserId,
        day: String,
        textTurns: Int,
        voiceSeconds: Int,
        inputTokens: Long,
        outputTokens: Long,
        at: Instant,
    ) {
        usage["$userId:$day"] = listOf(textTurns, voiceSeconds, inputTokens, outputTokens)
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

    override suspend fun tokens(userId: UserId, day: String) =
        (counters["i:$userId:$day"] ?: 0) to (counters["o:$userId:$day"] ?: 0)

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

    override suspend fun speak(text: String, language: String?): Resource<SpokenAudio> {
        if (language in unspoken) return Resource.error(IntegrationError.UnsupportedLanguage(language.orEmpty()))
        return Resource.success(SpokenAudio(text.toByteArray(), "audio/mpeg"))
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
    private val clock = Clock.systemUTC()
    val config = config

    private val agent = AshaAgent(model, tools, config, JsonReplyBlocks())
    private val ids = OrderedIds(clock)
    private val recorder = TurnRecorder(memory, pending, quota, config, ids, memory, clock)
    val dailyUsage = DailyUsage(quota, memory, memory, clock)

    val start = StartTurn(verifier, memory, plans, quota, agent, recorder, config, ids, clock)
    val resume = ContinueTurn(verifier, pending, agent, recorder, config, clock)

    /** A9 over another store of waiting turns, for races. */
    fun resumeWith(turns: PendingTurns) = ContinueTurn(verifier, turns, agent, recorder, config, clock)

    val list = ListChat(memory)
    val deleteAll = DeleteChat(memory, memory)
    val deleteOne = DeleteExchange(memory, memory, memory)
    val usage = GetUsage(UsageReader(plans, quota, config, clock))
    val transcribe = TranscribeVoice(speech, plans, quota, dailyUsage, config, clock)
    val speak = SynthesizeSpeech(speech, plans, quota, dailyUsage, config, clock)
    val deleteData = DeleteAssistantData(memory, memory, pending, quota, clock)
}

fun smallLimits(turns: Int = 2, context: Int = 4) = AssistantConfig(free = Limits(turns, 30, context))
