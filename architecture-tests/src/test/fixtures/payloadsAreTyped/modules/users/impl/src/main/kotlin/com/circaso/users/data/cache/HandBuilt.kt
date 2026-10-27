package com.circaso.users.data.cache

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class HandBuilt {
    fun frame(data: Map<String, Any>) = JsonObject(data.mapValues { JsonPrimitive(it.value.toString()) })

    fun body(id: String) = buildJsonObject { put("id", id) }

    fun read(text: String) = Json.parseToJsonElement(text).jsonObject["id"]?.jsonPrimitive?.content
}
