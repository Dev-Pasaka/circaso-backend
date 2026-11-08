package com.circaso.assistant.api

import kotlinx.serialization.Serializable

/** The assistant's realtime frames: the module, and each frame's `type`. */
object AssistantFrames {
    const val MODULE = "assistant"
    const val STATUS = "status"
}

/**
 * `assistant/status` (decision 2026-11-07), to each person who met Asha down for maintenance, once she is back:
 * [available] true, [since] when she came back (ISO-8601). Not replayed (`seq` 0): the app reads A14 on opening Asha.
 * Pushed when the app isn't open, as "Asha is back".
 */
@Serializable
data class AssistantStatusFrame(val available: Boolean, val since: String?)
