package com.circaso.messaging.presentation.controllers

import com.circaso.messaging.presentation.dto.AddOneTimeKeysRequest
import com.circaso.messaging.presentation.dto.KeyBundleResponse
import com.circaso.messaging.presentation.dto.KeyStatusResponse
import com.circaso.messaging.presentation.dto.OneTimePreKeysLeftResponse
import com.circaso.messaging.presentation.dto.PublishKeysRequest
import com.circaso.messaging.presentation.mappers.addOneTimeKeysCommand
import com.circaso.messaging.presentation.mappers.parseUserId
import com.circaso.messaging.presentation.mappers.publishKeysCommand
import com.circaso.messaging.presentation.mappers.toResponse
import com.circaso.messaging.presentation.services.KeyService
import com.circaso.shared.Scope
import com.circaso.shared.flatMap
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.principal
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

private const val TAG = "Encryption keys"
private const val SCOPE = "Scope: `app`."
private const val SIGNED =
    "Signed by the phone's device key: `deviceProof.signature` is the ES256 signature over the SHA-256 of the " +
        "body without `deviceProof`, and its `timestamp` must be within 5 minutes of the server clock."

/** The public-key directory, M1 to M3. Upload bodies are read as text: the signature covers their bytes. */
fun Route.keyController(keys: KeyService) {
    route("/keys") {
        authenticate(Scope.APP) {
            put {
                val command = publishKeysCommand(call.principal(), call.receiveText())
                val result = command.flatMap { keys.publish(it) }
                call.respondDefault(result.toDefaultResponse { OneTimePreKeysLeftResponse(it) })
            }.docs<PublishKeysRequest, OneTimePreKeysLeftResponse>(
                "M1 · Publish this phone's identity key, signed prekey and up to 100 one-time prekeys",
                TAG,
                listOf("messaging.keys_proof_invalid", "request.malformed"),
                notes =
                    "$SCOPE $SIGNED Replaces the whole set: one-time prekeys from an earlier upload are deleted. " +
                        "Rotate the signed prekey every 7 days with this call.",
            )

            post("one-time") {
                val command = addOneTimeKeysCommand(call.principal(), call.receiveText())
                val result = command.flatMap { keys.addOneTime(it) }
                call.respondDefault(result.toDefaultResponse { OneTimePreKeysLeftResponse(it) })
            }.docs<AddOneTimeKeysRequest, OneTimePreKeysLeftResponse>(
                "M1 · Top up one-time prekeys",
                TAG,
                listOf("messaging.keys_proof_invalid", "messaging.keys_not_found", "request.malformed"),
                notes =
                    "$SCOPE $SIGNED At most 100 are kept at once; an upload that would pass that is refused " +
                        "whole (`request.malformed`). Top up when `GET /keys/me` shows fewer than 20.",
            )

            get("me") {
                val principal = call.principal()
                val result = keys.status(principal.userId, principal.deviceId)
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<NoBody, KeyStatusResponse>(
                "M2 · How many one-time prekeys are left and when the signed prekey was set",
                TAG,
                listOf("messaging.keys_not_found"),
                notes = "$SCOPE `messaging.keys_not_found` until this phone has published its keys.",
            )

            get("{userId}") {
                val caller = call.principal().userId
                val result = parseUserId(call.parameters["userId"]).flatMap { keys.fetch(caller, it) }
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<NoBody, KeyBundleResponse>(
                "M3 · A person's key bundle, to start an encrypted session",
                TAG,
                listOf("users.not_reachable", "messaging.keys_not_found", "messaging.rate_limited"),
                notes =
                    "$SCOPE Uses up one of the person's one-time prekeys; `oneTimePreKey` is null when none are " +
                        "left. You must be able to reach the person. 60 bundles per user per hour.",
            )
        }
    }
}
