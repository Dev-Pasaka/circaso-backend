package com.circaso.integrations.impl.didit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DiditCanonicalJsonTest {
    @Test
    fun `the canonical form of a delivery is exactly what Didit's own recipe writes`() {
        assertEquals(DiditSamples.CANONICAL, DiditCanonicalJson.of(DiditSamples.RAW))
    }

    @Test
    fun `a body a proxy re-encoded (spaces, another key order, raw unicode) gives the same canonical form`() {
        val reencoded =
            """
            {
              "workflow_version": 4, "status": "Approved",
              "decision": { "status": "Approved", "id_verifications": [ {
                  "whole": 100.0, "small": 1e-05, "score": 87.42,
                  "note": "line\nbreak\ttab \u0001 """ + "\u007f" +
                """
                 é 😀", "neg": -0.0,
                      "last_name": "Ñandú \"Q\" \\ \/x", "first_name": "José", "big": 1e+16 } ] },
                  "metadata": { "tier": "premium", "alpha": [ 1, 2.5, 100.0, true, null ], "Zeta": 1 },
                  "application_id": "11111111-2222-3333-4444-555555555555", "created_at": 1795348794,
                  "environment": "sandbox", "event_id": "9c0c8b8a-1111-4222-9333-444444444444",
                  "sandbox_scenario": null, "session_id": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                  "timestamp": 1795348800, "vendor_data": "user:0193a1b2-0000-7000-8000-000000000001",
                  "webhook_type": "status.updated", "workflow_id": "66666666-7777-8888-9999-000000000000"
                }
                """.trimIndent()

        assertEquals(DiditSamples.CANONICAL, DiditCanonicalJson.of(reencoded))
    }

    @Test
    fun `keys sort by code point, a repeated key keeps its last value, and empty containers stay empty`() {
        assertEquals("""{"A":1,"B":2,"a":3,"é":4}""", DiditCanonicalJson.of("""{"é":4,"a":3,"B":2,"A":1}"""))
        assertEquals("""{"k":2}""", DiditCanonicalJson.of("""{"k":1,"k":2}"""))
        assertEquals("""{"a":[],"b":{}}""", DiditCanonicalJson.of("""{ "b" : { } , "a" : [ ] }"""))
        // U+FF5E sorts before U+1F600 by code point, though after it by UTF-16 unit.
        assertEquals("""{"～":1,"😀":2}""", DiditCanonicalJson.of("""{"😀":2,"～":1}"""))
    }

    @Test
    fun `numbers - integers as written, whole floats as integers, other floats as they came`() {
        assertEquals("[0,-7,12345678901234567890]", DiditCanonicalJson.of("[0,-7,12345678901234567890]"))
        assertEquals("[100,0,3,10000000000000000]", DiditCanonicalJson.of("[100.0,-0.0,3e0,1e+16]"))
        assertEquals("[87.42,1e-05,-2.5]", DiditCanonicalJson.of("[87.42,1e-05,-2.5]"))
    }

    @Test
    fun `text that isn't one well-formed JSON value has no canonical form`() {
        listOf(
            "",
            "not json",
            """{"a":1""",
            """{"a":1,}""",
            """{"a":01}""",
            """{"a":"unterminated}""",
            """{"a":"bad \x escape"}""",
            """{"a":1} trailing""",
            """{a:1}""",
            "[".repeat(100) + "]".repeat(100),
        ).forEach { assertNull(DiditCanonicalJson.of(it), it.take(20)) }
    }
}
