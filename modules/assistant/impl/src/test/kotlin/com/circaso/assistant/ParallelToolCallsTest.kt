package com.circaso.assistant

import com.circaso.assistant.data.blocks.JsonReplyBlocks
import com.circaso.assistant.domain.models.ToolStatus
import com.circaso.assistant.domain.ports.AgentTools
import com.circaso.assistant.domain.ports.PendingMessage
import com.circaso.assistant.domain.ports.ToolContext
import com.circaso.assistant.domain.support.AgentOutcome
import com.circaso.assistant.domain.support.AgentTurn
import com.circaso.assistant.domain.support.AshaAgent
import com.circaso.assistant.domain.support.AshaPrompt
import com.circaso.assistant.domain.support.AssistantConfig
import com.circaso.assistant.domain.support.ReplyListener
import com.circaso.integrations.api.ModelMessage
import com.circaso.integrations.api.ModelPurpose
import com.circaso.integrations.api.ToolCall
import com.circaso.integrations.api.ToolSpec
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A step's tool calls all run, together, and all their results go back (decision 2026-11-07). */
class ParallelToolCallsTest {
    /** Wallet, conversion and calculator stand-ins; a call can be held until [release] lets it answer. */
    private class Tools : AgentTools {
        val contexts = ConcurrentHashMap<String, ToolContext>()
        val started = CopyOnWriteArrayList<String>()
        val held = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        override fun specs(hasAgentToken: Boolean): List<ToolSpec> =
            listOf(AshaPrompt.getWallet, AshaPrompt.convertAmount, AshaPrompt.calculate, AshaPrompt.searchWeb)

        override fun handles(toolName: String) = toolName in specs(true).map { it.name }

        override fun label(toolName: String, argumentsJson: String) = "Looking ($toolName)…"

        override suspend fun call(
            toolName: String,
            argumentsJson: String,
            context: ToolContext,
        ): Resource<String> {
            started += argumentsJson
            contexts[argumentsJson] = context
            held[argumentsJson]?.await()
            return Resource.success("""{"tool":"$toolName","args":$argumentsJson}""")
        }
    }

    private class Heard : ReplyListener {
        val started = CopyOnWriteArrayList<ToolStatus>()
        val finished = CopyOnWriteArrayList<ToolStatus>()

        override suspend fun text(delta: String) = Unit

        override suspend fun toolStarted(status: ToolStatus) {
            started += status
        }

        override suspend fun toolFinished(status: ToolStatus, isOk: Boolean) {
            finished += status
        }
    }

    private val model = ScriptedModel()
    private val tools = Tools()
    private val agent = AshaAgent(model, tools, AssistantConfig(), JsonReplyBlocks(), MemoryScenes())

    private fun turn(deviceTools: Boolean = true) =
        AgentTurn(
            UUID.randomUUID(),
            UserId(UUID.randomUUID()),
            "agent-tok",
            "KES",
            deviceTools,
            ModelPurpose.VOICE,
            "2026-11-07",
        )

    private fun call(id: String, name: String) = ToolCall(id, name, """{"id":"$id"}""")

    private suspend fun run(
        vararg calls: ToolCall,
        heard: ReplyListener = ReplyListener.None,
        deviceTools: Boolean = true,
    ): AgentOutcome {
        model.streamed("Let me check.", toolCalls = calls.toList()).say("Done.")
        val r = agent.run(listOf(ModelMessage.User("how much is left?")), turn(deviceTools), listener = heard)
        return (r as Resource.Success).data
    }

    private fun results(messages: List<ModelMessage>) = messages.filterIsInstance<ModelMessage.ToolResult>()

    @Test
    fun `every call of a step runs, and every result goes back after the assistant's calls`() =
        runTest {
            val heard = Heard()
            val outcome =
                run(call("a", "getWallet"), call("b", "convertAmount"), call("c", "calculate"), heard = heard)

            assertIs<AgentOutcome.Answered>(outcome)
            assertEquals(listOf("getWallet", "convertAmount", "calculate"), outcome.toolsUsed)
            val second = model.requests[1].messages
            val calls = second.filterIsInstance<ModelMessage.Assistant>().single().toolCalls
            assertEquals(listOf("a", "b", "c"), calls.map { it.id })
            assertEquals(listOf("a", "b", "c"), results(second).map { it.callId }, "in the order called")
            assertTrue(results(second).none { it.content.startsWith("Skipped") })
            assertTrue(results(second).all { it.content.startsWith("<untrusted>") })
            assertEquals(3, heard.started.size)
            assertEquals(3, heard.finished.size)
        }

    @Test
    fun `the calls run at once, not one after another`() =
        runTest {
            // the first call waits until the second has started: one at a time would never finish
            val gate = CompletableDeferred<Unit>()
            tools.held["""{"id":"a"}"""] = gate
            model.streamed("", toolCalls = listOf(call("a", "getWallet"), call("b", "convertAmount"))).say("Done.")

            val run =
                async {
                    agent.run(listOf(ModelMessage.User("q")), turn())
                }
            while (tools.started.size < 2) yield()
            gate.complete(Unit)

            assertIs<AgentOutcome.Answered>((run.await() as Resource.Success).data)
            assertEquals(2, results(model.requests[1].messages).size)
        }

    @Test
    fun `each call is told the calls before it in the step, so a per-turn cap counts as before`() =
        runTest {
            run(call("a", "searchWeb"), call("b", "searchWeb"), call("c", "searchWeb"))

            assertEquals(emptyList(), tools.contexts.getValue("""{"id":"a"}""").calledBefore)
            assertEquals(listOf("searchWeb"), tools.contexts.getValue("""{"id":"b"}""").calledBefore)
            assertEquals(listOf("searchWeb", "searchWeb"), tools.contexts.getValue("""{"id":"c"}""").calledBefore)
        }

    @Test
    fun `the cap also counts the calls of earlier steps`() =
        runTest {
            model
                .streamed("", toolCalls = listOf(call("a", "searchWeb")))
                .streamed("", toolCalls = listOf(call("b", "getWallet"), call("c", "searchWeb")))
                .say("Done.")

            agent.run(listOf(ModelMessage.User("q")), turn())

            assertEquals(
                listOf("searchWeb", "getWallet"),
                tools.contexts.getValue("""{"id":"c"}""").calledBefore,
            )
        }

    @Test
    fun `a phone call among server calls parks the turn with the server results already in`() =
        runTest {
            val heard = Heard()
            val outcome =
                run(call("a", "getWallet"), call("d", "searchChats"), call("b", "calculate"), heard = heard)

            val waiting = assertIs<AgentOutcome.WaitingForDevice>(outcome)
            assertEquals("d", waiting.call.id)
            assertEquals(listOf("getWallet", "searchChats", "calculate"), waiting.toolsUsed)
            assertEquals(listOf("""{"id":"a"}""", """{"id":"b"}"""), tools.started.sorted())
            val tail = waiting.conversation.takeLast(2)
            assertEquals(
                listOf("a", "b"),
                tail.map { (it as PendingMessage.ToolResult).callId },
                "the server results end the conversation, so the phone's answer joins them",
            )
            assertEquals(listOf("getWallet", "calculate", "searchChats"), heard.started.map { it.tool })
            assertEquals(1, model.requests.size, "no model call until the phone answers")
        }

    @Test
    fun `a second phone call in the same step is answered as skipped, the first parks`() =
        runTest {
            val outcome = run(call("d1", "searchChats"), call("d2", "readChat"))

            val waiting = assertIs<AgentOutcome.WaitingForDevice>(outcome)
            assertEquals("d1", waiting.call.id)
            val last = waiting.conversation.last() as PendingMessage.ToolResult
            assertEquals("d2", last.callId)
            assertTrue(last.content.startsWith("Skipped:"))
        }

    @Test
    fun `phone tools that are off, and unknown tools, are answered as not available and don't park`() =
        runTest {
            val outcome =
                run(call("d", "searchChats"), call("x", "moveMoney"), call("a", "getWallet"), deviceTools = false)

            assertIs<AgentOutcome.Answered>(outcome)
            val back = results(model.requests[1].messages).associate { it.callId to it.content }
            assertEquals("That tool is not available.", back["d"])
            assertEquals("That tool is not available.", back["x"])
            assertTrue(back.getValue("a").startsWith("<untrusted>"))
        }
}
