package com.circaso.assistant

import com.circaso.assistant.data.blocks.JsonReplyBlocks
import com.circaso.assistant.domain.ports.BlockKind
import com.circaso.assistant.domain.support.BlockFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rich blocks (decision 2026-10-31): checked, repaired or dropped on the server, never sent broken. */
class ReplyBlocksTest {
    private val blocks = JsonReplyBlocks()

    /** The text as the user would see it when the model writes [pieces], however they are split. */
    private fun shown(vararg pieces: String, isVoice: Boolean = false): String {
        val filter = BlockFilter(blocks, isVoice)
        return pieces.joinToString("") { filter.feed(it) } + filter.finish()
    }

    /** The same text split into pieces of every size from 1 to 7 characters gives the same result. */
    private fun shownInAnySplit(text: String, isVoice: Boolean = false): String {
        val results =
            (1..7).map { size -> shown(*text.chunked(size).toTypedArray(), isVoice = isVoice) } +
                shown(text, isVoice = isVoice)
        assertEquals(1, results.distinct().size, "the split changed the result: $results")
        return results.first()
    }

    @Test
    fun `a good chart passes in canonical form, whatever the stream's split`() {
        val text =
            "Here is your month:\n```chart\n" +
                """{"type":"BAR","title":"Out","unit":"KES","series":[{"name":"Sent","points":""" +
                """[{"label":"Sep","value":4200},{"label":"Oct","value":"3,100.5"}]}]}""" +
                "\n```\nThat's all."

        val out = shownInAnySplit(text)

        assertEquals(
            "Here is your month:\n```chart\n" +
                """{"type":"bar","title":"Out","unit":"KES","series":[{"name":"Sent","points":""" +
                """[{"label":"Sep","value":4200.0},{"label":"Oct","value":3100.5}]}]}""" +
                "\n```\nThat's all.",
            out,
        )
    }

    @Test
    fun `a broken block is dropped and the text around it stays`() {
        val text = "Before.\n```chart\n{\"type\":\"bar\",\"series\":[{\"name\":\"x\",\"points\":[{\"lab\n```\nAfter."

        assertEquals("Before.\nAfter.", shownInAnySplit(text))
    }

    @Test
    fun `a block the model never closed is checked at the end, and ordinary code fences pass through`() {
        val unclosed =
            "Look:\n```diagram\n" +
                """{"nodes":[{"id":"a","label":"You"},{"id":"b","label":"Mum"}],"edges":[{"from":"a","to":"b"}]}"""
        assertEquals(
            "Look:\n```diagram\n" +
                """{"title":"","nodes":[{"id":"a","label":"You"},{"id":"b","label":"Mum"}],""" +
                """"edges":[{"from":"a","to":"b"}]}""" + "\n```\n",
            shownInAnySplit(unclosed),
        )

        val code = "Run:\n```kotlin\nval x = 1\n```\nDone."
        assertEquals(code, shownInAnySplit(code))
    }

    @Test
    fun `a spoken reply carries no blocks and no markdown marks`() {
        val text = "### Balance\nYou have **20 USDC**.\n```chart\n{\"series\":[]}\n```\nThat's `it`."

        assertEquals("Balance\nYou have 20 USDC.\nThat's it.", shownInAnySplit(text, isVoice = true))
    }

    @Test
    fun `chart repairs are safe ones only`() {
        val pie =
            blocks.clean(
                BlockKind.CHART,
                """{"type":"pie","series":[{"name":"a","points":""" +
                    """[{"label":"x","value":-1},{"label":"y","value":2}]},""" +
                    """{"name":"b","points":[{"label":"z","value":1}]}]}""",
            )!!
        assertTrue(pie.contains(""""points":[{"label":"y","value":2.0}]"""), pie)
        assertTrue(!pie.contains("\"b\""), "a pie has one series")
        assertEquals(
            """{"type":"bar","title":"","series":[{"name":"","points":[{"label":"1","value":1.0}]}]}""",
            blocks.clean(BlockKind.CHART, """{"type":"donut","series":[{"points":[{"label":1,"value":1}]}]}"""),
        )
        assertNull(blocks.clean(BlockKind.CHART, """{"type":"bar","series":[{"points":[{"label":"x"}]}]}"""))
        assertNull(blocks.clean(BlockKind.CHART, "not json"))
        val many = (1..100).joinToString(",") { """{"label":"$it","value":$it}""" }
        val capped = blocks.clean(BlockKind.CHART, """{"series":[{"name":"n","points":[$many]}]}""")!!
        assertEquals(60, Regex("\"label\"").findAll(capped).count())
    }

    @Test
    fun `a statement keeps only rows that read, with amounts exact`() {
        val clean =
            blocks.clean(
                BlockKind.STATEMENT,
                """{"title":"October","period":{"from":"2026-10-01","to":"2026-10-31"},"currency":"kes",""" +
                    """"rows":[{"date":"2026-10-02T10:00:00Z","description":"To Bob","direction":"out",""" +
                    """"amount":"20.50","asset":"usdc","fiat":"2,654.75"},""" +
                    """{"date":"yesterday","amount":"1","asset":"USDC"},""" +
                    """{"date":"2026-10-03","amount":"-5","asset":"USDC"}],""" +
                    """"totals":[{"label":"Out","amount":20.5,"asset":"USDC"}]}""",
            )

        assertEquals(
            """{"title":"October","period":{"from":"2026-10-01","to":"2026-10-31"},"currency":"KES",""" +
                """"rows":[{"date":"2026-10-02","description":"To Bob","direction":"OUT","amount":"20.5",""" +
                """"asset":"USDC","fiat":"2654.75"}],"totals":[{"label":"Out","amount":"20.5","asset":"USDC"}]}""",
            clean,
        )
        assertNull(blocks.clean(BlockKind.STATEMENT, """{"title":"Empty","rows":[]}"""))
    }

    @Test
    fun `a diagram keeps at most 8 nodes and only edges between them`() {
        val nodes = (1..12).joinToString(",") { """{"id":"n$it","label":"Node $it"}""" }
        val clean =
            blocks.clean(
                BlockKind.DIAGRAM,
                """{"title":"Flow","nodes":[$nodes,{"id":"n1","label":"again"}],""" +
                    """"edges":[{"from":"n1","to":"n2","label":"KES 500"},{"from":"n1","to":"n11"},""" +
                    """{"from":"n3","to":"n3"},{"from":"n2","to":"ghost"}]}""",
            )!!

        assertEquals(8, Regex("\"id\"").findAll(clean).count())
        assertTrue(clean.endsWith(""""edges":[{"from":"n1","to":"n2","label":"KES 500"}]}"""), clean)
        assertNull(blocks.clean(BlockKind.DIAGRAM, """{"nodes":[{"id":"a","label":"Alone"}],"edges":[]}"""))
    }
}
