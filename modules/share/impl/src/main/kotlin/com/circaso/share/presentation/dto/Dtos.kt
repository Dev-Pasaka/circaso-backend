package com.circaso.share.presentation.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * SH1. `kind` is `receipt` (with `transferId`), `statement` (with `chatId`), `invite`, `pay_me` or `address` (with
 * `blockchain`). `showNames` (receipts, statements, addresses) puts names, and a statement's notes, on the page.
 * `currency` (an ISO 4217 code) adds the value in that currency to a receipt or statement. `expiresInDays` (1 to 365)
 * ends the link; without it the link opens until it is revoked (an invite's ends with its invite, in 7 days).
 */
@Serializable
data class CreateShareLinkRequest(
    val kind: String,
    val transferId: String? = null,
    val chatId: String? = null,
    val blockchain: String? = null,
    val showNames: Boolean = true,
    val currency: String? = null,
    val expiresInDays: Int? = null,
)

/** A link as its owner sees it: where it points, what it is called and when it stops opening. */
@Serializable
data class ShareLinkDto(
    val code: String,
    val url: String,
    val kind: String,
    val title: String,
    val summary: String,
    val createdAt: String,
    val expiresAt: String? = null,
)

@Serializable
data class ShareLinksResponse(val links: List<ShareLinkDto>, val nextCursor: String? = null)

@Serializable
data class RevokedResponse(val revoked: Boolean)

/**
 * SH4: what the page shows, for the app to show natively, plus `open`, where this viewer's app may go from it (null
 * when nowhere: not their payment, not their group, or their own invite).
 */
@Serializable
data class SharedLinkResponse(
    val code: String,
    val url: String,
    val kind: String,
    val title: String,
    val summary: String,
    val createdAt: String,
    val expiresAt: String? = null,
    val mine: Boolean,
    val receipt: ReceiptDto? = null,
    val statement: StatementDto? = null,
    val person: PersonDto? = null,
    val address: AddressDto? = null,
    val open: OpenDto? = null,
)

/** A payment from the sharer's side (`direction` `sent` or `received`); `status` is the wallet's. */
@Serializable
data class ReceiptDto(
    val direction: String,
    val amount: String,
    val asset: String,
    val fiat: FiatAmountDto? = null,
    val status: String,
    val blockchain: String,
    val txHash: String? = null,
    val explorerUrl: String? = null,
    val at: String,
    val fromName: String? = null,
    val toName: String? = null,
)

@Serializable
data class FiatAmountDto(val currency: String, val amount: String)

/** USDC amounts in whole units ("120.5"); `fiat.rate` is one US dollar in `fiat.currency`, when it was shared. */
@Serializable
data class StatementDto(
    val goalName: String,
    val asset: String,
    val target: String,
    val raised: String,
    val paidOut: String,
    val held: String,
    val fiat: FiatRateDto? = null,
    val memberCount: Int,
    val deadline: String? = null,
    val lines: List<StatementLineDto>,
)

@Serializable
data class FiatRateDto(val currency: String, val rate: String)

/** `direction` `in` (a contribution) or `out` (a payout). */
@Serializable
data class StatementLineDto(
    val at: String,
    val direction: String,
    val amount: String,
    val name: String? = null,
    val note: String? = null,
)

@Serializable
data class PersonDto(val displayName: String, val handle: String? = null)

@Serializable
data class AddressDto(
    val blockchain: String,
    val address: String,
    val asset: String,
    val name: String? = null,
)

/**
 * `screen`: `transfer` (`transferId`), `chama` (`chatId`), `invite` (`userId`, `displayName`, `inviteToken` for U11
 * `via: invite`), `pay` (`userId`, `displayName`, `handle`) or `pay_address` (`address`, `blockchain`).
 */
@Serializable
data class OpenDto(
    val screen: String,
    val transferId: String? = null,
    val chatId: String? = null,
    val userId: String? = null,
    val handle: String? = null,
    val inviteToken: String? = null,
    val displayName: String? = null,
    val address: String? = null,
    val blockchain: String? = null,
)

/** One statement of `/.well-known/assetlinks.json` (Android App Links). */
@Serializable
data class AssetLinkDto(val relation: List<String>, val target: AssetLinkTargetDto)

@Serializable
data class AssetLinkTargetDto(
    val namespace: String,
    @SerialName("package_name") val packageName: String,
    @SerialName("sha256_cert_fingerprints") val fingerprints: List<String>,
)

/** `/.well-known/apple-app-site-association` (iOS Universal Links): `components` for iOS 13+, `paths` before. */
@Serializable
data class AppSiteAssociationDto(val applinks: AppLinksDto)

@Serializable
data class AppLinksDto(val apps: List<String> = emptyList(), val details: List<AppLinksDetailDto>)

@Serializable
data class AppLinksDetailDto(
    val appIDs: List<String>,
    val components: List<AppLinksComponentDto>,
    val paths: List<String>,
)

@Serializable
data class AppLinksComponentDto(
    @SerialName("/") val path: String,
    val comment: String,
)
