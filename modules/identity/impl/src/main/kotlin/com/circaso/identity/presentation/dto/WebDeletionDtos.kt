package com.circaso.identity.presentation.dto

import kotlinx.serialization.Serializable

/**
 * I17: the website's deletion request. [idToken] is the Google or Apple ID token the page got from signing in, with
 * [nonce] from I1 passed to Google or Apple when the sign-in began. [reason] is one of `not_useful`, `privacy`,
 * `too_many_apps`, `switching`, `problems`, `other`; [details] is optional, at most 500 characters.
 */
@Serializable
data class WebDeletionRequest(
    val provider: String,
    val idToken: String,
    val nonce: String? = null,
    val reason: String,
    val details: String? = null,
)

/**
 * I17's answer. [status] is `scheduled`, `already_scheduled`, `no_account` or `refused`. [deleteAfter] (ISO-8601) is
 * set for the first two; [code] and [message] only for `refused` (e.g. `wallet.funds_remaining`).
 */
@Serializable
data class WebDeletionResponse(
    val status: String,
    val deleteAfter: String? = null,
    val code: String? = null,
    val message: String? = null,
)
