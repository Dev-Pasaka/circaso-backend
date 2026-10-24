package com.circaso.integrations.impl.acme

import java.net.http.HttpClient
import io.ktor.client.engine.cio.CIO

class Bad {
    private val jdk = HttpClient.newHttpClient()
    private val own = HttpClient(CIO)
}
