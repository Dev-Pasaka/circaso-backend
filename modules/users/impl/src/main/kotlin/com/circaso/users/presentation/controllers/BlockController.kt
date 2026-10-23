package com.circaso.users.presentation.controllers

import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.SharedError
import com.circaso.users.presentation.dto.BlocksResponse
import com.circaso.users.presentation.dto.ReachResponse
import com.circaso.users.presentation.mappers.parseUserId
import com.circaso.users.presentation.mappers.toBlocks
import com.circaso.users.presentation.services.BlockService
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import com.circaso.web.userId
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put

private const val TAG = "Blocks and reach"
private const val SCOPE = "Scope: `app`."

/** Blocks and the reach check. U13 to U15. */
fun Route.blockController(blocks: BlockService) {
    authenticate(Scope.APP) {
        route("/blocks") {
            get {
                call.respondDefault(blocks.listBlocks(call.userId()).toDefaultResponse { it.toBlocks() })
            }.docs<NoBody, BlocksResponse>("U13 · People you have blocked", TAG, notes = SCOPE)

            route("/{userId}") {
                put {
                    val target = parseUserId(call.parameters["userId"])
                    call.respondDefault((if (target == null) invalidUserId() else blocks.block(call.userId(), target)).toDefaultResponse { it })
                }.docs<NoBody, NoBody>("U14 · Block someone; a block overrides every other way of reaching a person", TAG, listOf("users.cannot_block_self"), notes = SCOPE)

                delete {
                    val target = parseUserId(call.parameters["userId"])
                    call.respondDefault((if (target == null) invalidUserId() else blocks.unblock(call.userId(), target)).toDefaultResponse { it })
                }.docs<NoBody, NoBody>("U14 · Lift a block", TAG, notes = SCOPE)
            }
        }

        route("/me") {
            get("reach/{userId}") {
                val target = parseUserId(call.parameters["userId"])
                val result: Resource<Boolean> = if (target == null) invalidUserId() else blocks.checkReach(call.userId(), target)
                call.respondDefault(result.toDefaultResponse { ReachResponse(it) })
            }.docs<NoBody, ReachResponse>("U15 · Whether you can reach that person", TAG, notes = "$SCOPE Never says why not.")
        }
    }
}

private fun <T> invalidUserId(): Resource<T> = Resource.error(SharedError.Malformed(mapOf("userId" to "is not valid")))
