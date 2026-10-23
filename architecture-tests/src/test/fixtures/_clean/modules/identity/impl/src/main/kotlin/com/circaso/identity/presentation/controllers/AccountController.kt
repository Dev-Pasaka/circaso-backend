package com.circaso.identity.presentation.controllers

import com.circaso.identity.presentation.dto.AccountResponse
import com.circaso.identity.presentation.services.AccountService
import com.circaso.web.respondDefault
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.accountController(accounts: AccountService) {
    route("/account") {
        get {
            accounts.get(call.userId())
        }
        get("security") {
            accounts.get(call.userId())
        }
    }
}
