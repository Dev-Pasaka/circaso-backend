package com.circaso.assistant.presentation.controllers

import com.circaso.assistant.presentation.dto.AssistantStatusResponse
import com.circaso.assistant.presentation.mappers.toResponse
import com.circaso.assistant.presentation.services.StatusService
import com.circaso.shared.Scope
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/** A14: whether Asha can answer now, or is down for maintenance (decision 2026-11-07). */
fun Route.statusController(status: StatusService) {
    authenticate(Scope.APP) {
        route("/assistant") {
            get("status") {
                call.respondDefault(status.status().toDefaultResponse { it.toResponse() })
            }.docs<NoBody, AssistantStatusResponse>(
                "A14 · Whether Asha can answer now",
                "Asha voice and usage",
                notes =
                    "Scope: `app` (the user token). `available: false` means Asha is down for maintenance (her " +
                        "model can't answer; A2, A9 and the live socket say `assistant.unavailable`); `since` is " +
                        "when that last changed (ISO-8601), null if she has never been down. Read it when Asha's " +
                        "screens open or come back to the foreground, and while showing her as down; the socket's " +
                        "`assistant` `status` frame, or the \"Asha is back\" push, says when she is back.",
            )
        }
    }
}
