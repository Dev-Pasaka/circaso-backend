package com.circaso.assistant.api

/**
 * The assistant publishes on `events:assistant`. Phase A (read only) publishes nothing; Phase B adds
 * `ProposalCreated`, `ProposalConfirmed` and `ProposalDeclined` here, for monitoring and the transaction auditor.
 */
object AssistantStream {
    const val PRODUCER = "assistant"
}
