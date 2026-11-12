package com.circaso.identity.presentation.controllers

import com.circaso.identity.presentation.dto.GroupKeysResponse
import com.circaso.identity.presentation.dto.KeyBackupRootResponse
import com.circaso.identity.presentation.dto.SaveGroupKeyRequest
import com.circaso.identity.presentation.dto.SaveKeyBackupRootRequest
import com.circaso.identity.presentation.dto.SaveSavedNamesRequest
import com.circaso.identity.presentation.dto.SavedNamesResponse
import com.circaso.identity.presentation.dto.SavedNamesVersionResponse
import com.circaso.identity.presentation.mappers.toCommand
import com.circaso.identity.presentation.mappers.toPrincipal
import com.circaso.identity.presentation.mappers.toResponse
import com.circaso.identity.presentation.mappers.toVersionResponse
import com.circaso.identity.presentation.services.KeyBackupService
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

/** KB5's page when the phone names none; the use case caps it at 500. */
private const val DEFAULT_GROUP_KEYS_PAGE = 200

private const val BLOB_NOTES =
    "The server never sees a key: everything stored is opaque bytes the phone sealed."

/**
 * Group key backup (2026-11-12): each group's meta key, encrypted on the phone under a random backup root key, and that
 * root sealed under the circaso.com passkey's PRF key. KB1 to KB5. KB6 and KB7 keep the person's names for their
 * contacts as one blob sealed under the same root.
 */
fun Route.keyBackupController(keys: KeyBackupService) {
    route("/key-backup") {
        authenticate(Scope.APP) {
            get("root") {
                val result = keys.root(call.principal().userId)
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<NoBody, KeyBackupRootResponse>(
                "KB1 · The sealed backup root, or `exists: false` when group key backup is off",
                TAG,
                notes =
                    "Scope: `app`. Not sensitive (it opens nothing without the passkey): nothing is logged, " +
                        "nobody is emailed. Answers 200 with `exists: false` rather than 404. $BLOB_NOTES",
            )

            put("root") {
                val result =
                    call
                        .receive<SaveKeyBackupRootRequest>()
                        .toCommand(call.principal().toPrincipal())
                        .flatMap { keys.saveRoot(it) }
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<SaveKeyBackupRootRequest, KeyBackupRootResponse>(
                "KB2 · Turn group key backup on, or replace its root",
                TAG,
                listOf(
                    "key_backup.version_conflict",
                    "key_backup.credential_id_invalid",
                    "key_backup.salt_invalid",
                    "key_backup.sealed_root_invalid",
                    "key_backup.algorithm_invalid",
                    "key_backup.expected_version_invalid",
                    "request.malformed",
                ),
                notes =
                    "Scope: `app`, with an `Idempotency-Key`. One root per account: the first save is version 1 " +
                        "(logged `KEY_BACKUP_ON`) and each replace adds one (`KEY_BACKUP_CHANGED`). Send " +
                        "`expectedVersion` (KB1's `version`) when replacing, so a root another phone saved meanwhile " +
                        "is refused (`key_backup.version_conflict`, 409). `credentialId` is base64url (at most 1024 " +
                        "characters), `salt` base64 of 16 to 64 bytes, `sealedRoot` base64 of at most 1 KiB, " +
                        "`algorithm` at most 64 of `A-Z a-z 0-9 . _ + / -`. Group keys are kept as they are: when " +
                        "the root key itself changes, upload them again under it. Answers KB1's shape.",
            )

            delete {
                call.respondDefault(keys.remove(call.principal().toPrincipal()).toDefaultResponse { it })
            }.docs<NoBody, NoBody>(
                "KB3 · Turn group key backup off: delete its root and every group key",
                TAG,
                notes =
                    "Scope: `app`, with an `Idempotency-Key`. Answers 200 with `data: null`, also when there is " +
                        "nothing. Logged (`KEY_BACKUP_OFF`) when there was something.",
            )

            route("/saved-names") {
                get {
                    val result = keys.savedNames(call.principal().userId)
                    call.respondDefault(result.toDefaultResponse { it.toResponse() })
                }.docs<NoBody, SavedNamesResponse>(
                    "KB6 · Your saved names for your contacts, sealed",
                    TAG,
                    notes =
                        "Scope: `app`. Logs nothing. With none saved: `exists: false`, `version: 0`, `ciphertext` " +
                            "and `updatedAt` null (200, not 404). `version` is what KB7's `baseVersion` must be. " +
                            BLOB_NOTES,
                )

                put {
                    val result =
                        call
                            .receive<SaveSavedNamesRequest>()
                            .toCommand(call.principal().toPrincipal())
                            .flatMap { keys.putSavedNames(it) }
                    call.respondDefault(result.toDefaultResponse { it.toVersionResponse() })
                }.docs<SaveSavedNamesRequest, SavedNamesVersionResponse>(
                    "KB7 · Back up your saved names: the whole sealed blob, compare-and-set",
                    TAG,
                    listOf(
                        "identity.saved_names_conflict",
                        "identity.key_backup_off",
                        "identity.saved_names_ciphertext_invalid",
                        "identity.saved_names_base_version_invalid",
                        "request.malformed",
                    ),
                    notes =
                        "Scope: `app`, with an `Idempotency-Key`. `ciphertext` is standard base64 of 1 byte to 512 " +
                            "KiB, AES-256-GCM under the backup root key, replacing the whole blob. `baseVersion` is " +
                            "KB6's `version` (0 when none): any other answers " +
                            "`identity.saved_names_conflict` (409), so read again, merge and retry. Only while key " +
                            "backup is on (`identity.key_backup_off`, 409). Answers `{ version }`, which is " +
                            "`baseVersion + 1`. KB3 and account deletion delete it.",
                )
            }

            route("/group-keys") {
                get {
                    val query = call.request.queryParameters
                    val limit = query["limit"]?.toIntOrNull() ?: DEFAULT_GROUP_KEYS_PAGE
                    val result = keys.groupKeys(call.principal().userId, query["cursor"], limit)
                    call.respondDefault(result.toDefaultResponse { it.toResponse() })
                }.docs<NoBody, GroupKeysResponse>(
                    "KB5 · Your backed-up group keys, a page at a time",
                    TAG,
                    listOf("request.malformed"),
                    notes =
                        "Scope: `app`. Query: `cursor` (the last page's `nextCursor`), `limit` (default 200, at " +
                            "most 500). Ordered by `chatId`, then `metaVersion`; `nextCursor` is null on the last " +
                            "page. $BLOB_NOTES",
                )

                put("{chatId}/{metaVersion}") {
                    val result =
                        call
                            .receive<SaveGroupKeyRequest>()
                            .toCommand(
                                call.principal().toPrincipal(),
                                call.parameters["chatId"],
                                call.parameters["metaVersion"],
                            ).flatMap { keys.putGroupKey(it) }
                    call.respondDefault(result.toDefaultResponse { it })
                }.docs<SaveGroupKeyRequest, NoBody>(
                    "KB4 · Back up one group's meta key at one metaVersion",
                    TAG,
                    listOf(
                        "key_backup.not_found",
                        "key_backup.chat_not_found",
                        "key_backup.too_many",
                        "key_backup.chat_id_invalid",
                        "key_backup.meta_version_invalid",
                        "key_backup.ciphertext_invalid",
                        "request.malformed",
                    ),
                    notes =
                        "Scope: `app`, with an `Idempotency-Key`. `ciphertext` is base64 of at most 512 bytes, the " +
                            "meta key encrypted under the backup root key. An upsert: the same blob again changes " +
                            "nothing, another blob replaces it. Only for a group you are in, or one you already " +
                            "backed up a key of (`key_backup.chat_not_found` otherwise); only while backup is on " +
                            "(`key_backup.not_found` before KB2 or after KB3); at most 10,000 keys per account " +
                            "(`key_backup.too_many`). Answers 200 with `data: null`.",
                )
            }
        }
    }
}
