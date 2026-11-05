package com.circaso.status.api

import kotlinx.serialization.Serializable

// What status' read-only agent route (`/v1/agent/status/…`, decision 2026-11-05) answers, read by the assistant over
// HTTP with the agent token, as it reads users' (decision 2026-10-27). A moment is end-to-end encrypted: only when it
// was posted and how it was received ever leave, never what it says or shows. Times are ISO-8601 UTC.

/** One of the user's own live moments: `hasMedia` says it carries a photo or a video; who viewed it is not given. */
@Serializable
data class AgentMomentDto(
    val momentId: String,
    val postedAt: String,
    val expiresAt: String,
    val hasMedia: Boolean,
    val viewCount: Int,
    val reactionCount: Int,
)

/** The user's own live moments, newest first (at most 50), and their views and reactions added up. */
@Serializable
data class AgentMomentsResponse(
    val live: Int,
    val totalViews: Int,
    val totalReactions: Int,
    val moments: List<AgentMomentDto>,
)
