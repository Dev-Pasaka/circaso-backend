package com.circaso.integrations.impl.didit

/**
 * One delivery and its signatures, made outside this code base with the recipe Didit documents (docs.didit.me →
 * Webhooks → Signature verification), in Python:
 *
 * - the body Didit transmits: `json.dumps(payload, sort_keys=True, separators=(",", ":"))` (ASCII-escaped);
 * - `X-Signature`: HMAC-SHA256 of those exact bytes;
 * - `X-Signature-V2`: HMAC-SHA256 of `json.dumps(shorten_floats(payload), sort_keys=True, separators=(",", ":"),
 *   ensure_ascii=False)`, where `shorten_floats` turns a whole-valued float into an integer.
 *
 * So these are reference vectors, not this code checking itself. The payload has everything the canonical form must
 * get right: keys to sort (an upper-case one sorts first), accents and an emoji, an escaped quote and backslash, a
 * control character, floats that are whole (`100.0`, `1e+16`, `-0.0`) and floats that aren't (`87.42`, `1e-05`).
 */
internal object DiditSamples {
    const val SECRET = "test-webhook-secret"

    /** The `timestamp` inside [RAW]: 2026-11-22T12:00:00Z. */
    const val SENT_AT = 1_795_348_800L
    const val EVENT_ID = "9c0c8b8a-1111-4222-9333-444444444444"
    const val SESSION_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    /** The body as Didit transmits it (raw strings: every backslash here is one in the body). */
    const val RAW =
        """{"application_id":"11111111-2222-3333-4444-555555555555","created_at":1795348794,"decision""" +
            """":{"id_verifications":[{"big":1e+16,"first_name":"Jos\u00e9","last_name":"\u00d1and\u00fa """ +
            """\"Q\" \\ /x","neg":-0.0,"note":"line\nbreak\ttab \u0001 \u007f \u00e9 \ud83d\ude00","score""" +
            """":87.42,"small":1e-05,"whole":100.0}],"status":"Approved"},"environment":"sandbox","event_""" +
            """id":"9c0c8b8a-1111-4222-9333-444444444444","metadata":{"Zeta":1,"alpha":[1,2.5,100.0,true,""" +
            """null],"tier":"premium"},"sandbox_scenario":null,"session_id":"aaaaaaaa-bbbb-cccc-dddd-eeee""" +
            """eeeeeeee","status":"Approved","timestamp":1795348800,"vendor_data":"user:0193a1b2-0000-700""" +
            """0-8000-000000000001","webhook_type":"status.updated","workflow_id":"66666666-7777-8888-999""" +
            """9-000000000000","workflow_version":4}"""

    /** [RAW] in Didit's canonical form, as Python wrote it (ordinary strings: the escapes here are Kotlin's). */
    const val CANONICAL =
        "{\"application_id\":\"11111111-2222-3333-4444-555555555555\",\"cr" +
            "eated_at\":1795348794,\"decision\":{\"id_verifications\":[{\"big\":" +
            "10000000000000000,\"first_name\":\"Jos\u00E9\",\"last_name\":\"\u00D1and\u00FA \\\"Q\\\" \\\\ /x\",\"" +
            "neg\":0,\"note\":\"line\\nbreak\\ttab \\u0001 \u007F \u00E9 \uD83D\uDE00\",\"score\":87.42," +
            "\"small\":1e-05,\"whole\":100}],\"status\":\"Approved\"},\"environmen" +
            "t\":\"sandbox\",\"event_id\":\"9c0c8b8a-1111-4222-9333-44444444444" +
            "4\",\"metadata\":{\"Zeta\":1,\"alpha\":[1,2.5,100,true,null],\"tier\"" +
            ":\"premium\"},\"sandbox_scenario\":null,\"session_id\":\"aaaaaaaa-b" +
            "bbb-cccc-dddd-eeeeeeeeeeee\",\"status\":\"Approved\",\"timestamp\":" +
            "1795348800,\"vendor_data\":\"user:0193a1b2-0000-7000-8000-00000" +
            "0000001\",\"webhook_type\":\"status.updated\",\"workflow_id\":\"6666" +
            "6666-7777-8888-9999-000000000000\",\"workflow_version\":4}"

    /** `X-Signature-V2` for [RAW] under [SECRET]. */
    const val SIGNATURE_V2 = "22d7ed8866193dd8274ba1d24282ba67b3b8bb3cba5254111dc65097a927f00e"

    /** `X-Signature` for [RAW] under [SECRET]. */
    const val SIGNATURE_RAW = "0ea229af028442b8a9ff3f0f11ff84f81157d07e6a9afbd87724ede5bf020570"
}
