package com.circaso.users.data.cache

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class HandBuilt {
    fun frame(data: Map<String, Any>) = JsonObject(data.mapValues { JsonPrimitive(it.value.toString()) })

    fun body(id: String) = buildJsonObject { put("id", id) }

    fun list(id: String) = buildJsonArray { add(JsonPrimitive(id)) }

    fun read(text: String) = Json.parseToJsonElement(text).jsonObject["id"]?.jsonPrimitive?.content

    fun first(element: JsonElement) = element.jsonArray.first()

    fun cast(element: JsonElement) = (element as JsonObject).size

    fun maybe(element: JsonElement) = (element as? JsonObject)?.size ?: (element as? JsonPrimitive)?.content
}

/** The ways round a typed class: raw serializers, decoding into an element, a field kept as one. */
class Untyped(val blob: JsonObject) {
    fun asObject(text: String) = Json.decodeFromString(JsonObject.serializer(), text)

    fun asElement(text: String) = Json.decodeFromString(JsonElement.serializer(), text)

    fun generic(text: String) = Json.decodeFromString<JsonObject>(text)

    fun read(decoder: JsonDecoder) = decoder.decodeJsonElement()

    fun write(encoder: JsonEncoder, value: JsonElement) = encoder.encodeJsonElement(value)
}
