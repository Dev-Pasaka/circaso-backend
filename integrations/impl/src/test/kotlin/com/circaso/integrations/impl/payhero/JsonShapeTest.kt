package com.circaso.integrations.impl.payhero

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonShapeTest {
    @Test
    fun `a body becomes its field names and value kinds, sorted, with every value gone`() {
        val body =
            """{"success":true,"status":"success","reference":"UFD004139536.eO","external_reference":"order-9",""" +
                """"amount":10.5,"transaction_id":"","phone":"+254712345678","callback_urls":null}"""

        val shape = JsonShape.of(body)

        assertEquals(
            "{amount:number,callback_urls:null,external_reference:string,phone:string,reference:string," +
                "status:string,success:bool,transaction_id:string}",
            shape,
        )
        listOf("254712345678", "UFD004139536", "order-9", "10.5").forEach { assertFalse(it in shape, it) }
    }

    @Test
    fun `nesting and lists keep their layout`() {
        assertEquals(
            "{forward_url:string,response:{Amount:number,Phone:string,ResultCode:number},status:bool}",
            JsonShape.of(
                """{"forward_url":"","response":{"Amount":1300,"Phone":"+254712345678","ResultCode":0},"status":true}""",
            ),
        )
        assertEquals(
            "{invalid_arguments:[{field:string,value:string}],none:[],tags:[string|number]}",
            JsonShape.of(
                """{"invalid_arguments":[{"field":"a","value":""},{"value":"","field":"b"}],""" +
                    """"tags":["a",1,"b"],"none":[]}""",
            ),
        )
        assertEquals("[{price:number}]", JsonShape.of(""" [ {"price": 0} , {"price": -1.5e3} ] """))
    }

    @Test
    fun `a field name that looks like a number or an id is masked, and odd characters are replaced`() {
        assertEquals(
            "{#:string,63:string,a?b:number}",
            JsonShape.of("""{"254712345678":"x","63":"owner","a b":1}"""),
        )
        assertEquals("{say??hi??:string}", JsonShape.of("""{"say\"hi\"":"an escaped \" quote, a } and a ]"}"""))
    }

    @Test
    fun `anything that isn't JSON says so, and nothing throws or runs away`() {
        assertEquals("not-json", JsonShape.of("<html>Bad Gateway</html>"))
        assertEquals("not-json", JsonShape.of("""{"a":"""))
        assertEquals("not-json", JsonShape.of("""{"a":1}trailing"""))
        assertEquals("not-json", JsonShape.of("""{"a":bogus}"""))
        assertEquals("empty", JsonShape.of("  "))
        assertEquals("not-json", JsonShape.of("[".repeat(10_000)))
        assertEquals(
            "{a:{b:{c:{d:{e:{f:{…}}}}}}}",
            JsonShape.of("""{"a":{"b":{"c":{"d":{"e":{"f":{"g":{"h":1}}}}}}}}"""),
        )

        val wide = (1..500).joinToString(",", "{", "}") { """"field_$it":"v"""" }
        assertTrue(JsonShape.of(wide).length <= 700)
    }
}
