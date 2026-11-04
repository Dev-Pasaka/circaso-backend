package com.circaso.assistant

import com.circaso.assistant.domain.models.MascotActivity
import com.circaso.assistant.domain.models.MascotHints
import com.circaso.assistant.domain.models.MascotMood
import com.circaso.assistant.domain.models.MascotReaction
import com.circaso.assistant.domain.models.MascotScene
import com.circaso.assistant.domain.models.MascotSceneState
import com.circaso.assistant.domain.models.ToolStatus
import com.circaso.assistant.domain.models.TurnCommand
import com.circaso.assistant.domain.models.TurnResult
import com.circaso.assistant.domain.ports.BlockKind
import com.circaso.assistant.domain.support.MascotTag
import com.circaso.assistant.domain.support.MascotTagFilter
import com.circaso.assistant.domain.support.ReplyListener
import com.circaso.assistant.domain.support.TurnMascot
import com.circaso.assistant.presentation.controllers.chatController
import com.circaso.assistant.presentation.services.ChatService
import com.circaso.integrations.api.ToolCall
import com.circaso.shared.DeviceId
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.web.CallPrincipal
import com.circaso.web.CallPrincipalKey
import com.circaso.web.installJson
import com.circaso.web.installStatusPages
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Asha's mascot hints (decision 2026-11-04): the tag, validation, hysteresis, the sass rule, and the wire. */
class MascotTest {
    // ---- the tag in the stream ---------------------------------------------------------------------------------

    private fun filtered(vararg pieces: String): Pair<String, List<MascotTag>> {
        val filter = MascotTagFilter()
        val text = StringBuilder()
        val tags = mutableListOf<MascotTag>()
        (pieces.map { filter.feed(it) } + filter.finish()).forEach {
            text.append(it.text)
            tags += it.tags
        }
        return text.toString() to tags
    }

    @Test
    fun `the tag is taken out of the text, even split across pieces, and its line goes with it`() {
        val (text, tags) =
            filtered(
                "<mas",
                "cot scene=\"gym\" mood=\"happy\" ",
                "activity=\"lifting\"/>\nNice ",
                "work!",
            )

        assertEquals("Nice work!", text)
        assertEquals(MascotTag(MascotScene.GYM, MascotMood.HAPPY, MascotActivity.LIFTING), tags.single())
    }

    @Test
    fun `ordinary angle brackets pass through`() {
        val (text, tags) = filtered("1 < 2 and <b>bold</b>, <3 and <mascots> ", "<ma")

        assertEquals("1 < 2 and <b>bold</b>, <3 and <mascots> <ma", text)
        assertTrue(tags.isEmpty())
    }

    @Test
    fun `a tag that never closes is never shown`() {
        val (text, tags) = filtered("Hi. <mascot scene=\"gym\" mood=")

        assertEquals("Hi. ", text)
        assertTrue(tags.isEmpty())
    }

    @Test
    fun `anything not on the lists is dropped, never forwarded`() {
        val tag =
            MascotTag.parse(
                """ scene="Mars" mood="angry" activity="dancing" reaction="confetti" sensitive="yes" price="2000" """,
            )

        assertEquals(MascotTag(), tag)
        assertEquals(
            MascotTag(MascotScene.BEACH_SUNSET, MascotMood.SASSY, null, MascotReaction.THANKS, true, true),
            MascotTag.parse("scene=BEACH_SUNSET mood='sassy' activity=\"\" reaction=thanks sensitive=true strong=true"),
        )
    }

    // ---- hysteresis --------------------------------------------------------------------------------------------

    private val t1 = UUID.randomUUID()
    private val t2 = UUID.randomUUID()
    private val t3 = UUID.randomUUID()

    @Test
    fun `a conversation's first scene is taken at once`() {
        val state = TurnMascot.decideScene(MascotSceneState.NEW, MascotScene.GYM, strong = false, t1)

        assertEquals(MascotScene.GYM, state.scene)
    }

    @Test
    fun `a new scene needs two turns in a row, and one off-topic turn doesn't move it`() {
        val office = MascotSceneState(scene = MascotScene.OFFICE)

        val once = TurnMascot.decideScene(office, MascotScene.BEACH, strong = false, t1)
        assertEquals(MascotScene.OFFICE, once.scene, "one turn about the beach: no flicker")
        assertEquals(MascotScene.BEACH, once.pending)

        val back = TurnMascot.decideScene(once, MascotScene.OFFICE, strong = false, t2)
        assertEquals(MascotScene.OFFICE, back.scene)
        assertNull(back.pending, "back on topic: the pending scene is forgotten")

        val again =
            TurnMascot.decideScene(
                TurnMascot.decideScene(back, MascotScene.BEACH, false, t3),
                MascotScene.BEACH,
                false,
                UUID.randomUUID(),
            )
        assertEquals(MascotScene.BEACH, again.scene, "two turns in a row: the scene changes")
    }

    @Test
    fun `different new scenes on consecutive turns don't add up, and a strong change switches at once`() {
        val office = MascotSceneState(scene = MascotScene.OFFICE)
        val gym = TurnMascot.decideScene(office, MascotScene.GYM, false, t1)
        val beach = TurnMascot.decideScene(gym, MascotScene.BEACH, false, t2)
        assertEquals(MascotScene.OFFICE, beach.scene)
        assertEquals(MascotScene.BEACH, beach.pending)

        assertEquals(MascotScene.CRYPTO, TurnMascot.decideScene(office, MascotScene.CRYPTO, true, t1).scene)
    }

    @Test
    fun `a turn is counted once, even when it carries on after the phone answered`() {
        val office = MascotSceneState(scene = MascotScene.OFFICE)
        val first = TurnMascot.decideScene(office, MascotScene.GYM, false, t1)
        val sameTurn = TurnMascot.decideScene(first, MascotScene.GYM, false, t1)

        assertEquals(MascotScene.OFFICE, sameTurn.scene)
        assertEquals(MascotScene.GYM, sameTurn.pending)
    }

    @Test
    fun `a turn without a scene breaks the run`() {
        val office = MascotSceneState(scene = MascotScene.OFFICE)
        val pending = TurnMascot.decideScene(office, MascotScene.GYM, false, t1)
        val mascot = TurnMascot(pending, t2, "ok")
        mascot.done()
        assertNull(mascot.state.pending)
        assertEquals(MascotScene.OFFICE, mascot.state.scene)
    }

    // ---- sass and sensitivity ----------------------------------------------------------------------------------

    @Test
    fun `a sensitive turn is never sassy`() {
        val hints =
            TurnMascot(MascotSceneState.NEW, t1, "how much did I spend").proposed(
                MascotTag(MascotScene.OFFICE, MascotMood.SASSY, sensitive = true),
            )

        assertEquals(MascotMood.CARING, hints.mood)
        assertTrue(hints.sensitive)
    }

    @Test
    fun `a question that reads like hardship is sensitive whatever the model says`() {
        val mascot = TurnMascot(MascotSceneState.NEW, t1, "My payment failed and I have a debt to pay")
        val hints = mascot.proposed(MascotTag(MascotScene.OFFICE, MascotMood.SASSY))

        assertTrue(hints.sensitive)
        assertEquals(MascotMood.CARING, hints.mood)
        assertTrue(TurnMascot.isHardship("Sina pesa leo"))
        assertTrue(TurnMascot.isHardship("what are the fees?"))
        assertFalse(TurnMascot.isHardship("what is my balance"))
        assertFalse(TurnMascot.isHardship("Adam sent me money"), "whole words: ada is not Adam")
        // no tag at all: a caring Asha
        assertEquals(MascotMood.CARING, TurnMascot(MascotSceneState.NEW, t1, "my mum died").replying().mood)
    }

    @Test
    fun `support is never sassy`() {
        val inSupport = MascotSceneState(scene = MascotScene.SUPPORT)
        assertEquals(
            MascotMood.CARING,
            TurnMascot(inSupport, t1, "it is not working").proposed(MascotTag(mood = MascotMood.SASSY)).mood,
        )
        assertEquals(
            MascotMood.CARING,
            TurnMascot(MascotSceneState(scene = MascotScene.OFFICE), t1, "help")
                .proposed(MascotTag(MascotScene.SUPPORT, MascotMood.SASSY))
                .mood,
            "proposing support is enough",
        )
    }

    @Test
    fun `one sassy beat per conversation`() {
        val first = TurnMascot(MascotSceneState(scene = MascotScene.OFFICE), t1, "third coffee this week, oops")
        assertEquals(MascotMood.SASSY, first.proposed(MascotTag(mood = MascotMood.SASSY)).mood)
        assertEquals(MascotMood.SASSY, first.done().mood, "the turn keeps its beat")
        assertTrue(first.state.hadSass)

        val next = TurnMascot(first.state, t2, "and a fourth")
        assertEquals(MascotMood.CALM, next.proposed(MascotTag(mood = MascotMood.SASSY)).mood)
    }

    // ---- activity ----------------------------------------------------------------------------------------------

    @Test
    fun `while a tool runs the activity comes from the tool, otherwise thinking`() {
        assertEquals(MascotActivity.SEARCHING_WEB, TurnMascot.activityOf("searchWeb"))
        assertEquals(MascotActivity.READING_TRANSACTIONS, TurnMascot.activityOf("listTransactions"))
        assertEquals(MascotActivity.READING_TRANSACTIONS, TurnMascot.activityOf("summarizeTransactions"))
        assertEquals(MascotActivity.READING_TRANSACTIONS, TurnMascot.activityOf("getTransaction"))
        assertEquals(MascotActivity.THINKING, TurnMascot.activityOf("searchContacts"))
        assertEquals(MascotActivity.THINKING, TurnMascot.activityOf("searchChats"))

        val mascot = TurnMascot(MascotSceneState.NEW, t1, "q")
        assertEquals(MascotActivity.MAKING_CHART, mascot.block(BlockKind.CHART).activity)
        assertEquals(MascotActivity.WRITING_STATEMENT, mascot.block(BlockKind.STATEMENT).activity)
        assertEquals(MascotActivity.SPEAKING, mascot.block(null).activity)
        assertEquals(MascotActivity.THINKING, mascot.toolFinished().activity)
        assertEquals(MascotActivity.DONE, mascot.done().activity)
    }

    @Test
    fun `a reaction is sent once`() {
        val mascot = TurnMascot(MascotSceneState.NEW, t1, "thank you!")
        assertEquals(MascotReaction.THANKS, mascot.proposed(MascotTag(reaction = MascotReaction.THANKS)).reaction)
        assertNull(mascot.done().reaction)
    }

    // ---- through the agent -------------------------------------------------------------------------------------

    private val h = Harness()

    private fun ask(text: String) = TurnCommand(h.owner, text, null, true, "agent-tok", "req-1")

    private sealed interface Heard {
        data class Text(val delta: String) : Heard

        data class Mascot(val hints: MascotHints) : Heard

        data class Done(val hints: MascotHints) : Heard

        data class Tool(val status: ToolStatus, val isDone: Boolean) : Heard
    }

    private class Recorder : ReplyListener {
        val heard = CopyOnWriteArrayList<Heard>()

        override suspend fun text(delta: String) {
            heard += Heard.Text(delta)
        }

        override suspend fun toolStarted(status: ToolStatus) {
            heard += Heard.Tool(status, isDone = false)
        }

        override suspend fun toolFinished(status: ToolStatus, isOk: Boolean) {
            heard += Heard.Tool(status, isDone = true)
        }

        override suspend fun mascot(hints: MascotHints) {
            heard += Heard.Mascot(hints)
        }

        override suspend fun mascotDone(hints: MascotHints) {
            heard += Heard.Done(hints)
        }
    }

    @Test
    fun `the hints come before the first text, the tag is never saved, and the turn ends on done`() =
        runTest {
            h.model.streamed(
                "<mascot scene=\"beach\" mood=\"excited\" activity=\"sipping\"/>\n",
                "Mombasa sounds great!",
            )
            val recorder = Recorder()

            val reply =
                assertIs<TurnResult.Answer>((h.start(ask("plan a trip to Mombasa"), recorder) as Resource.Success).data)

            assertEquals("Mombasa sounds great!", reply.message.text)
            val first = assertIs<Heard.Mascot>(recorder.heard.first()).hints
            assertEquals(MascotHints(MascotScene.BEACH, MascotMood.EXCITED, MascotActivity.SIPPING), first)
            assertEquals(Heard.Text("Mombasa sounds great!"), recorder.heard[1])
            assertEquals(MascotActivity.DONE, assertIs<Heard.Done>(recorder.heard.last()).hints.activity)
            assertEquals(
                MascotScene.BEACH,
                h.scenes.kept.values
                    .single()
                    .scene,
                "the conversation keeps its scene",
            )
        }

    @Test
    fun `without a tag the turn still gets its hints, after the tool frames and before the text`() =
        runTest {
            h.model
                .streamed(
                    "",
                    toolCalls = listOf(ToolCall("c1", "searchContacts", """{"query":"mum"}""")),
                ).say("Mum is Mum Wanjiku.")
            val recorder = Recorder()

            h.start(ask("who is mum"), recorder)

            val tools = recorder.heard.filterIsInstance<Heard.Tool>()
            assertEquals(
                MascotActivity.THINKING,
                tools
                    .first { !it.isDone }
                    .status.mascot
                    ?.activity,
            )
            assertEquals(
                MascotActivity.THINKING,
                tools
                    .first { it.isDone }
                    .status.mascot
                    ?.activity,
            )
            val beforeText = recorder.heard.takeWhile { it !is Heard.Text }.last()
            assertEquals(
                MascotHints(MascotScene.OFFICE, MascotMood.CALM, MascotActivity.SPEAKING),
                (beforeText as Heard.Mascot).hints,
            )
        }

    @Test
    fun `A2 sends mascot events, and the fields on tool_status and tool_done`() =
        testApplication {
            val chat = ChatService(h.start, h.resume, h.list, h.deleteAll, h.deleteOne)
            application {
                installJson()
                installStatusPages()
                intercept(ApplicationCallPipeline.Plugins) {
                    call.attributes.put(
                        CallPrincipalKey,
                        CallPrincipal(h.owner, DeviceId.new(), UUID.randomUUID(), Scope.APP),
                    )
                }
                routing { route("/v1") { chatController(chat) } }
            }
            h.model
                .call("searchContacts")
                .streamed(
                    "<mascot scene=\"office\" mood=\"concerned\" reaction=\"payment_failed\" sensitive=\"true\"/>",
                    "That one failed.",
                )

            val body =
                client
                    .post("/v1/assistant/chat/messages") {
                        contentType(ContentType.Application.Json)
                        header("X-Agent-Token", "agent-tok")
                        setBody("""{"text":"why did my payment not go?"}""")
                    }.bodyAsText()

            val lines = body.lines()
            val events = lines.filter { it.startsWith("event: ") }.map { it.removePrefix("event: ") }
            assertEquals(listOf("tool_status", "tool_done", "mascot", "message", "mascot", "done"), events)
            val data = lines.filter { it.startsWith("data: ") }.map { it.removePrefix("data: ") }
            assertTrue(""""activity":"thinking"""" in data[0] && """"scene":"office"""" in data[0], data[0])
            assertTrue(""""activity":"thinking"""" in data[1], data[1])
            assertEquals(
                """{"scene":"office","mood":"concerned","activity":"speaking","reaction":"payment_failed","sensitive":true}""",
                data[2],
            )
            assertEquals("""{"delta":"That one failed."}""", data[3])
            assertEquals(
                """{"scene":"office","mood":"concerned","activity":"done","reaction":null,"sensitive":true}""",
                data[4],
            )
            assertFalse("<mascot" in body)
        }
}
