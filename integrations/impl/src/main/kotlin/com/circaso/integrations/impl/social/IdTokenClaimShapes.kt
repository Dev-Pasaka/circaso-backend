package com.circaso.integrations.impl.social

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.booleanOrNull

/*
 * Two ID-token claims Google and Apple send in more than one JSON shape. kotlinx's JsonTransformingSerializer reshapes
 * the raw element before the typed class reads it, so this one file touches JSON elements and is allow-listed in
 * `payloadsAreTyped`; the claims themselves stay a typed class (IdTokenClaims).
 */

/** Reads `aud` as a list whether the provider sent one string or several. */
internal object AudienceSerializer : JsonTransformingSerializer<List<String>>(ListSerializer(String.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonArray) element else JsonArray(listOf(element))
}

/** `true` for a JSON boolean `true` or the string "true", `false` for anything else. */
internal object LooseFlagSerializer : JsonTransformingSerializer<Boolean>(Boolean.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val primitive = element as? JsonPrimitive ?: return JsonPrimitive(false)
        return JsonPrimitive(primitive.booleanOrNull ?: (primitive.content == "true"))
    }
}
