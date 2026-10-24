package com.circaso.assistant

import com.circaso.assistant.domain.models.ChatMessage
import com.circaso.assistant.domain.models.Limits
import com.circaso.assistant.domain.models.Plan
import com.circaso.assistant.domain.ports.AgentTools
import com.circaso.assistant.domain.ports.PendingTurn
import com.circaso.assistant.domain.ports.PendingTurns
import com.circaso.assistant.domain.ports.QuotaCounters
import com.circaso.assistant.domain.repositories.AssistantChanges
import com.circaso.assistant.domain.repositories.AssistantStore
import com.circaso.assistant.domain.repositories.AuditEntry
import com.circaso.assistant.domain.repositories.ChatRepository
import com.circaso.assistant.domain.repositories.EntitlementRepository
import com.circaso.assistant.domain.support.AshaAgent
import com.circaso.assistant.domain.support.AshaPrompt
import com.circaso.assistant.domain.support.AssistantConfig
import com.circaso.assistant.domain.support.OrderedIds
import com.circaso.assistant.domain.support.TurnRecorder
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
import com.circaso.integrations.api.ModelUsage
import com.circaso.integrations.api.SpeechToText
import com.circaso.integrations.api.SpokenAudio
import com.circaso.integrations.api.TextToSpeech
import com.circaso.integrations.api.ToolCall
import com.circaso.integrations.api.ToolSpec
import com.circaso.integrations.api.Transcript
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Clock
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class MemoryAssistant :
    ChatRepository,
    AssistantStore {
    val messages = CopyOnWriteArrayList<ChatMessage>()
    val audit = CopyOnWriteArrayList<AuditEntry>()
    val usage = ConcurrentHashMap<String, List<Number>>()

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
        changes.deletedMessages.forEach { gone -> messages.removeAll { it.messageId == gone.messageId } }
        audit += changes.audit
    }

    override suspend fun deleteChat(userId: UserId) {
        messages.removeAll { it.userId == userId }
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

    override suspend fun countTextTurn(userId: UserId, day: String) = add("t:$userId:$day", 1)

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
}

class MemoryPending : PendingTurns {
    private val turns = ConcurrentHashMap<Pair<UserId, UUID>, PendingTurn>()

    override suspend fun save(turn: PendingTurn) {
        turns[turn.userId to turn.turnId] = turn
    }

    override suspend fun take(userId: UserId, turnId: UUID) = turns.remove(userId to turnId)
}

class Plans(var plan: Plan = Plan.FREE) : EntitlementRepository {
    override suspend fun planOf(userId: UserId) = plan
}

class FakeTools : AgentTools {
    data class Called(val name: String, val arguments: String, val token: String)

    val calls = CopyOnWriteArrayList<Called>()
    var answer: (
        String,
    ) -> Resource<String> = { Resource.success("""{"contacts":[{"userId":"u1","displayName":"Mum Wanjiku"}]}""") }

    override fun specs(): List<ToolSpec> = listOf(AshaPrompt.searchContacts, AshaPrompt.listContacts)

    override fun handles(toolName: String) = toolName in setOf("searchContacts", "listContacts")

    override suspend fun call(
        toolName: String,
        argumentsJson: String,
        agentToken: String,
    ): Resource<String> {
        calls += Called(toolName, argumentsJson, agentToken)
        return answer(toolName)
    }
}

/** A model that follows a script and records what it was asked. */
class ScriptedModel : LanguageModel {
    val requests = CopyOnWriteArrayList<ModelRequest>()
    private val steps = ArrayDeque<(ModelRequest) -> Resource<ModelResponse>>()

    fun then(step: (ModelRequest) -> Resource<ModelResponse>) = apply { steps.add(step) }

    fun say(text: String) = then { Resource.success(ModelResponse(text, emptyList(), ModelUsage(100, 20))) }

    fun call(
        name: String,
        arguments: String = "{}",
        id: String = "call-$name",
    ) = then { Resource.success(ModelResponse(null, listOf(ToolCall(id, name, arguments)), ModelUsage(100, 10))) }

    fun fail() = then { Resource.error(IntegrationError.Unavailable("model")) }

    override suspend fun complete(request: ModelRequest): Resource<ModelResponse> {
        requests += request
        return steps.pollFirst()?.invoke(request)
            ?: Resource.success(ModelResponse("(unscripted)", emptyList(), ModelUsage(1, 1)))
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

    override suspend fun speak(text: String, language: String?) =
        Resource.success(
            SpokenAudio(text.toByteArray(), "audio/mpeg"),
        )
}

class Harness(config: AssistantConfig = AssistantConfig()) {
    val memory = MemoryAssistant()
    val quota = MemoryQuota()
    val pending = MemoryPending()
    val plans = Plans()
    val tools = FakeTools()
    val model = ScriptedModel()
    val speech = RecordingSpeech()
    private val clock = Clock.systemUTC()
    val config = config

    private val agent = AshaAgent(model, tools, config)
    private val ids = OrderedIds(clock)
    private val recorder = TurnRecorder(memory, pending, quota, config, ids, clock)

    val start = StartTurn(memory, plans, quota, agent, recorder, config, ids, clock)
    val resume = ContinueTurn(pending, agent, recorder, config)
    val list = ListChat(memory)
    val deleteAll = DeleteChat(memory)
    val deleteOne = DeleteExchange(memory, memory)
    val usage = GetUsage(plans, quota, config, clock)
    val transcribe = TranscribeVoice(speech, plans, quota, config, clock)
    val speak = SynthesizeSpeech(speech, plans, quota, config, clock)
    val deleteData = DeleteAssistantData(memory)
}

fun smallLimits(turns: Int = 2, context: Int = 4) = AssistantConfig(free = Limits(turns, 30, context))
