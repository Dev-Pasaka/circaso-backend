package com.circaso.assistant.presentation.dto

import kotlinx.serialization.Serializable

/**
 * A14: whether Asha can answer now (decision 2026-11-07). [available] false is "down for maintenance"; [since] is when
 * that last changed (ISO-8601), null if she has never been down.
 */
@Serializable
data class AssistantStatusResponse(val available: Boolean, val since: String?)
