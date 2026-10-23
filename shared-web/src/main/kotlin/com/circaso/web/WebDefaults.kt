package com.circaso.web

import com.circaso.shared.CircasoJson
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation

/** JSON through kotlinx-serialization only, configured once (Conventions → JSON). */
fun Application.installJson() {
    install(ContentNegotiation) {
        json(CircasoJson)
    }
}
