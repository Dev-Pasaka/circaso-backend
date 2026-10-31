package com.circaso.assistant.data.tools

import com.circaso.assistant.domain.ports.AgentTools
import com.circaso.assistant.domain.ports.ToolContext
import com.circaso.integrations.api.ToolSpec
import com.circaso.shared.Resource

/** Asha's server tools as one: each call goes to the set that handles it. */
class AllAgentTools(private val sets: List<AgentTools>) : AgentTools {
    override fun specs(hasAgentToken: Boolean): List<ToolSpec> = sets.flatMap { it.specs(hasAgentToken) }

    override fun handles(toolName: String) = sets.any { it.handles(toolName) }

    override fun label(toolName: String, argumentsJson: String): String =
        sets.firstOrNull { it.handles(toolName) }?.label(toolName, argumentsJson) ?: "Looking that up…"

    override suspend fun call(
        toolName: String,
        argumentsJson: String,
        context: ToolContext,
    ): Resource<String> = sets.first { it.handles(toolName) }.call(toolName, argumentsJson, context)
}
