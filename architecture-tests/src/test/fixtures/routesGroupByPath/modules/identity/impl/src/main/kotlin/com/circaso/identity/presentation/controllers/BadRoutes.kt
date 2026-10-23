package com.circaso.identity.presentation.controllers

import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.orders() {
    route("/order") {
        get("{id}") { }          // fine: inherits /order
    }
    get("/order/{id}") { }       // breaks the rule: repeats the parent's path
    post("/order") { }           // breaks the rule: should be post { } inside route("/order")
}
