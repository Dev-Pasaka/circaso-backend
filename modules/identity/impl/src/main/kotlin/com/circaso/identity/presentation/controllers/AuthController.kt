package com.circaso.identity.presentation.controllers

import com.circaso.identity.presentation.dto.JwksResponse
import com.circaso.identity.presentation.dto.NonceResponse
import com.circaso.identity.presentation.dto.RefreshRequest
import com.circaso.identity.presentation.dto.RefreshResponse
import com.circaso.identity.presentation.dto.SignInResponse
import com.circaso.identity.presentation.dto.SocialSignInRequest
import com.circaso.identity.presentation.mappers.toCommand
import com.circaso.identity.presentation.mappers.toPrincipal
import com.circaso.identity.presentation.mappers.toResponse
import com.circaso.identity.presentation.services.AuthService
import com.circaso.shared.Scope
import com.circaso.shared.flatMap
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.principal
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private const val TAG = "Sign in"
private const val NONCE_SECONDS = 300L

/** Sign-in, refresh, sign-out and the public keys. I1, I2, I7, I8 and the JWKS. */
fun Route.authController(auth: AuthService) {
    route("/auth") {
        // No token yet.
        post("nonce") {
            call.respondDefault(auth.nonce().toDefaultResponse { NonceResponse(it, NONCE_SECONDS) })
        }.docs<NoBody, NonceResponse>(
            "I1 · One-time nonce for the Google or Apple request, valid 5 minutes",
            TAG,
            notes = "No token needed.",
        )

        post("social") {
            val result = call.receive<SocialSignInRequest>().toCommand().flatMap { auth.signIn(it) }
            call.respondDefault(result.toDefaultResponse { it.toResponse() })
        }.docs<SocialSignInRequest, SignInResponse>(
            "I2 · Verify the Google or Apple ID token and learn the next step",
            TAG,
            listOf(
                "identity.nonce_invalid",
                "identity.provider_token_invalid",
                "identity.recovery_pending",
                "identity.account_suspended",
                "identity.account_blocked",
                "identity.account_deleted",
            ),
            notes =
                "No token needed. `next` is `VERIFY_PHONE`, `REVERIFY_PHONE`, `REGISTER_DEVICE` or `DONE`; the " +
                    "session is an `onboarding` session unless `next` is `DONE`.",
        )

        post("refresh") {
            val result = call.receive<RefreshRequest>().toCommand().flatMap { auth.refresh(it) }
            call.respondDefault(result.toDefaultResponse { it.toResponse() })
        }.docs<RefreshRequest, RefreshResponse>(
            "I7 · New token pair for a signed refresh request; the old refresh token dies",
            TAG,
            listOf("identity.session_revoked", "identity.device_proof_invalid"),
            notes = "No bearer token; the phone proves itself by signing `refreshToken|timestamp` with its device key.",
        )

        authenticate(Scope.APP) {
            post("signout") {
                call.respondDefault(auth.signOut(call.principal().toPrincipal()).toDefaultResponse { it })
            }.docs<NoBody, NoBody>("I8 · End this session; the phone stays registered", TAG, notes = "Scope: `app`.")
        }
    }

    route("/.well-known") {
        get("jwks.json") {
            call.respondDefault(auth.publicKeys().toDefaultResponse { it.toResponse() })
        }.docs<NoBody, JwksResponse>("Public keys the access tokens are signed with", TAG, notes = "No token needed.")
    }
}
