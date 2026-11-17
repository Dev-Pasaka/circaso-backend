package com.circaso.assistant

import com.circaso.assistant.domain.support.SpokenText
import kotlin.test.Test
import kotlin.test.assertEquals

class SpokenTextTest {
    @Test
    fun `a list number on its own is not said, and a step loses its mark`() {
        assertEquals("", SpokenText.shown("1."))
        assertEquals("", SpokenText.shown("2)"))
        assertEquals("Open the chat with them.", SpokenText.shown("1. Open the chat with them."))
        assertEquals("Open the chat with them.", SpokenText.shown("- Open the chat with them."))
        assertEquals("You have 5.", SpokenText.shown("You have 5."))
    }

    @Test
    fun `emphasis, code marks, headings and links are taken out, the words stay`() {
        assertEquals("Tell them not to pay.", SpokenText.shown("**Tell** _them_ `not` to pay."))
        assertEquals("Fees", SpokenText.shown("## Fees"))
        assertEquals(
            "See Circle: USDC for more.",
            SpokenText.shown("See [Circle: USDC](https://www.circle.com/usdc) for more."),
        )
        assertEquals("Read it at", SpokenText.shown("Read it at https://circaso.com/help"))
        assertEquals("your user_name stays", SpokenText.shown("your user_name stays"))
    }

    @Test
    fun `a hash amount is said as hash, and no other hash is said`() {
        assertEquals("Type hash 500 for lunch.", SpokenText.said(SpokenText.shown("Type `#500 for lunch`.")))
        assertEquals("Type #500 for lunch.", SpokenText.shown("Type `#500 for lunch`."))
        assertEquals("number one", SpokenText.said("# number one"))
    }

    @Test
    fun `a chat reply read aloud drops its blocks and table rules and keeps its sentences`() {
        val reply =
            """
            ## This month
            You spent **KES 2,590** (20 USDC).

            1. Rent
            2. Lunch

            | Who | Amount |
            |-----|--------|
            | Mum | 500 |

            ```chart
            {"type":"bar"}
            ```
            Send with `#500 for lunch`.
            """.trimIndent()

        assertEquals(
            "This month You spent KES 2,590 (20 USDC). Rent Lunch Who , Amount Mum , 500 Send with hash 500 for lunch.",
            SpokenText.ofReply(reply),
        )
    }
}
