package com.circaso.identity.presentation.agent

import com.circaso.identity.presentation.services.AccountService
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.agentAccountController(accounts: AccountService) {
    route("/agent/account") {
        get { accounts.get(call.userId()) }
        get("others") { accounts.findOthers(call.userId()) }
    }
}
