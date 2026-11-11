package com.circaso.identity.presentation.controllers

import com.circaso.identity.presentation.dto.SaveVaultRequest
import com.circaso.identity.presentation.dto.VaultResponse
import com.circaso.identity.presentation.dto.VaultStatusResponse
import com.circaso.identity.presentation.mappers.toCommand
import com.circaso.identity.presentation.mappers.toPrincipal
import com.circaso.identity.presentation.mappers.toResponse
import com.circaso.identity.presentation.mappers.toStatusResponse
import com.circaso.identity.presentation.services.VaultService
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
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route

private const val TAG = "Account and security"

/**
 * The PIN Vault (2026-11-11): the Circle wallet PIN, encrypted on the phone under a passkey's PRF key, kept here as a
 * blob the server can't read. V1 to V4.
 */
fun Route.vaultController(vaults: VaultService) {
    route("/vault") {
        authenticate(Scope.APP) {
            get("status") {
                val result = vaults.status(call.principal().userId)
                call.respondDefault(result.toDefaultResponse { it.toStatusResponse() })
            }.docs<NoBody, VaultStatusResponse>(
                "V1 · Whether you have a PIN Vault, with its passkey and version (never the blob)",
                TAG,
                notes =
                    "Scope: `app`. Cheap: read it to decide whether to offer a backup or a restore. Not an " +
                        "opening: nothing is logged, nobody is emailed and payments aren't paused.",
            )

            get {
                val result = vaults.open(call.principal().toPrincipal())
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<NoBody, VaultResponse>(
                "V2 · Open the PIN Vault: its blob, to decrypt on this phone",
                TAG,
                listOf("vault.not_found", "request.rate_limited"),
                notes =
                    "Scope: `app`. **Every successful call is an opening**: it is logged (`VAULT_OPENED` in I13), " +
                        "the owner is emailed, and **payments from their wallet pause for 24 hours** (W4 and W17 " +
                        "answer `wallet.on_hold`; W1's `hold` says until when). Fetch it only to decrypt it, right " +
                        "before restoring the PIN. At most 5 openings an hour per account (`request.rate_limited`, " +
                        "with `Retry-After`).",
            )

            put {
                val result =
                    call
                        .receive<SaveVaultRequest>()
                        .toCommand(call.principal().toPrincipal())
                        .flatMap { vaults.save(it) }
                call.respondDefault(result.toDefaultResponse { it.toStatusResponse() })
            }.docs<SaveVaultRequest, VaultStatusResponse>(
                "V3 · Create or replace the PIN Vault",
                TAG,
                listOf(
                    "vault.version_conflict",
                    "vault.credential_id_invalid",
                    "vault.salt_invalid",
                    "vault.ciphertext_invalid",
                    "vault.algorithm_invalid",
                    "vault.expected_version_invalid",
                    "request.malformed",
                ),
                notes =
                    "Scope: `app`, with an `Idempotency-Key`. One vault per account: the first save is version 1 and " +
                        "each replace adds one. Send `expectedVersion` (V1's `version`) when replacing, so a vault " +
                        "another phone saved meanwhile is refused (`vault.version_conflict`, 409) instead of " +
                        "overwritten; leave it out to save whatever is there. `credentialId` is base64url (at most " +
                        "1024 characters), `salt` base64 of 16 to 64 bytes, `ciphertext` base64 of at most 8 KiB, " +
                        "`algorithm` at most 64 of `A-Z a-z 0-9 . _ + / -`. Stored exactly as sent. Answers V1's shape.",
            )

            delete {
                call.respondDefault(vaults.remove(call.principal().toPrincipal()).toDefaultResponse { it })
            }.docs<NoBody, NoBody>(
                "V4 · Delete the PIN Vault",
                TAG,
                notes =
                    "Scope: `app`, with an `Idempotency-Key`. Succeeds when there is none. Logged (`VAULT_REMOVED`) " +
                        "when there was one.",
            )
        }
    }
}
