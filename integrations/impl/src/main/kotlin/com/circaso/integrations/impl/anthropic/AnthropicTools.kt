package com.circaso.integrations.impl.anthropic

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.executor.clients.anthropic.AnthropicCacheControl
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.ToolSpec
import com.circaso.shared.Resource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Asha's tools as Koog [ToolDescriptor]s. A [ToolSpec] carries its arguments as a JSON Schema string; it is read into
 * [SchemaNode], a typed subset of JSON Schema (objects, strings, enums, integers, numbers, booleans, arrays), which is
 * all Asha's tools use. Koog's descriptor has no `minimum`/`maximum`, so a range moves into the description ("1 to
 * 30"). A schema outside that subset is refused before any call, as Workers AI refuses one that isn't JSON.
 *
 * The last tool carries a cache breakpoint, so the tools (and with the system prompt's own breakpoint, the fixed
 * system prompt) are read from Anthropic's prompt cache on every call after the first.
 */
internal object AnthropicTools {
    private val json = Json { ignoreUnknownKeys = true }

    fun descriptors(specs: List<ToolSpec>): Resource<List<ToolDescriptor>> {
        val descriptors =
            specs.mapIndexed { i, spec ->
                val descriptor =
                    descriptor(spec)
                        ?: return Resource.error(
                            IntegrationError.Rejected(
                                "Tool '${spec.name}' has a parameters schema the model provider can't read.",
                            ),
                        )
                if (i == specs.lastIndex) descriptor.copy(cacheControl = AnthropicCacheControl.Default) else descriptor
            }
        return Resource.success(descriptors)
    }

    private fun descriptor(spec: ToolSpec): ToolDescriptor? {
        val schema =
            runCatching {
                json.decodeFromString(
                    SchemaNode.serializer(),
                    spec.parametersJsonSchema,
                )
            }.getOrNull()
        if (schema == null || (schema.type != null && schema.type != "object")) return null
        val parameters = schema.properties.map { (name, node) -> parameter(name, node) ?: return null }
        val (required, optional) = parameters.partition { it.name in schema.required }
        return ToolDescriptor(spec.name, spec.description, required, optional)
    }

    private fun parameter(name: String, node: SchemaNode): ToolParameterDescriptor? =
        type(node)?.let { ToolParameterDescriptor(name, describe(node), it) }

    private fun type(node: SchemaNode): ToolParameterType? =
        when (node.type) {
            "string" -> {
                node.enum?.let { ToolParameterType.Enum(it.toTypedArray()) } ?: ToolParameterType.String
            }

            "integer" -> {
                ToolParameterType.Integer
            }

            "number" -> {
                ToolParameterType.Float
            }

            "boolean" -> {
                ToolParameterType.Boolean
            }

            "array" -> {
                val items = node.items
                if (items ==
                    null
                ) {
                    ToolParameterType.List(ToolParameterType.String)
                } else {
                    type(items)?.let { ToolParameterType.List(it) }
                }
            }

            "object" -> {
                val properties = node.properties.map { (name, child) -> parameter(name, child) ?: return null }
                ToolParameterType.Object(properties, node.required)
            }

            else -> {
                null
            }
        }

    private fun describe(node: SchemaNode): String {
        val range =
            when {
                node.minimum != null && node.maximum != null -> "${plain(node.minimum)} to ${plain(node.maximum)}"
                node.minimum != null -> "at least ${plain(node.minimum)}"
                node.maximum != null -> "at most ${plain(node.maximum)}"
                else -> null
            }
        val description = node.description.orEmpty()
        return when {
            range == null -> description
            description.isEmpty() -> range.replaceFirstChar { it.uppercase() }
            else -> "$description ($range)"
        }
    }

    private fun plain(number: Double): String =
        if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()
}

/** The part of JSON Schema Asha's tool arguments use. */
@Serializable
internal data class SchemaNode(
    val type: String? = null,
    val description: String? = null,
    val properties: Map<String, SchemaNode> = emptyMap(),
    val required: List<String> = emptyList(),
    val items: SchemaNode? = null,
    val enum: List<String>? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
)
