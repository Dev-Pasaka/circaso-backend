package com.circaso.integrations.impl.workersai

import com.circaso.integrations.impl.FakeHttpClients
import com.circaso.integrations.impl.text
import io.ktor.client.request.HttpRequestData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** The Workers AI gateway the vendor tests share: account `acc123`, gateway `asha`, token `cf-token`. */
object WorkersAiKit {
    fun gateway(clients: FakeHttpClients) =
        WorkersAiGateway(
            http = clients.named("workers-ai") { baseUrl = WorkersAiGateway.baseUrl("acc123") },
            token = "cf-token",
            gateway = "asha",
        )

    /** A request's JSON body. */
    fun HttpRequestData.json(): JsonObject = Json.parseToJsonElement(text()).jsonObject
}
