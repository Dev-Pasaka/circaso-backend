package com.circaso.share.presentation.mappers

import com.circaso.messaging.api.ChatId
import com.circaso.share.api.ShareCode
import com.circaso.share.domain.errors.ShareError
import com.circaso.share.domain.models.CreateShareCommand
import com.circaso.share.domain.models.LinkCursor
import com.circaso.share.domain.models.LinkPage
import com.circaso.share.domain.models.OpenTarget
import com.circaso.share.domain.models.OpenedLink
import com.circaso.share.domain.models.ShareContent
import com.circaso.share.domain.models.ShareKind
import com.circaso.share.domain.models.ShareLink
import com.circaso.share.domain.support.Amounts
import com.circaso.share.domain.support.Explorers
import com.circaso.share.domain.support.ShareConfig
import com.circaso.share.presentation.dto.AddressDto
import com.circaso.share.presentation.dto.CreateShareLinkRequest
import com.circaso.share.presentation.dto.FiatAmountDto
import com.circaso.share.presentation.dto.FiatRateDto
import com.circaso.share.presentation.dto.OpenDto
import com.circaso.share.presentation.dto.PersonDto
import com.circaso.share.presentation.dto.ReceiptDto
import com.circaso.share.presentation.dto.ShareLinkDto
import com.circaso.share.presentation.dto.ShareLinksResponse
import com.circaso.share.presentation.dto.SharedLinkResponse
import com.circaso.share.presentation.dto.StatementDto
import com.circaso.share.presentation.dto.StatementLineDto
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import java.time.Instant
import java.util.Base64

private val BLOCKCHAIN = Regex("[A-Z0-9][A-Z0-9-]{1,31}")

/** SH1's shape: a known kind with the one id it needs, a currency code, 1 to [ShareConfig.maxDays] days. */
fun CreateShareLinkRequest.toCommand(ownerId: UserId, config: ShareConfig): Resource<CreateShareCommand> {
    val shareKind = kindOf(kind) ?: return Resource.error(ShareError.RequestInvalid)
    val days = expiresInDays
    val code = currency?.trim()?.uppercase()
    val chain = blockchain?.trim()?.uppercase()
    val isShapeWrong =
        (days != null && days !in 1..config.maxDays) ||
            (code != null && !Amounts.isCurrency(code)) ||
            (chain != null && !BLOCKCHAIN.matches(chain))
    if (isShapeWrong) return Resource.error(ShareError.RequestInvalid)
    val command =
        CreateShareCommand(
            ownerId = ownerId,
            kind = shareKind,
            transferId = transferId?.let { TransferId.parse(it) ?: return Resource.error(ShareError.RequestInvalid) },
            chatId = chatId?.let { ChatId.parse(it) ?: return Resource.error(ShareError.RequestInvalid) },
            blockchain = chain,
            showNames = showNames,
            currency = code,
            endsAfterDays = days,
        )
    return Resource.success(command)
}

fun parseCode(raw: String?): Resource<ShareCode> =
    ShareCode.parse(raw)?.let { Resource.success(it) } ?: Resource.error(ShareError.LinkNotFound)

/** `?limit=`: 1 to [ShareConfig.pageMax], [ShareConfig.pageDefault] when absent. */
fun parseLimit(raw: String?, config: ShareConfig): Resource<Int> {
    val limit = raw?.let { it.toIntOrNull() ?: return malformedLimit(config) } ?: config.pageDefault
    return if (limit in 1..config.pageMax) Resource.success(limit) else malformedLimit(config)
}

private fun malformedLimit(config: ShareConfig) =
    Resource.error(SharedError.Malformed("limit", "1 to ${config.pageMax}"))

/** `?cursor=` is the last link's time and code, base64url; anything else starts from the top. */
fun parseCursor(raw: String?): LinkCursor? {
    if (raw.isNullOrBlank()) return null
    val text = runCatching { String(Base64.getUrlDecoder().decode(raw)) }.getOrNull() ?: return null
    val millis = text.substringBefore('_').toLongOrNull() ?: return null
    val code = ShareCode.parse(text.substringAfter('_')) ?: return null
    return LinkCursor(Instant.ofEpochMilli(millis), code)
}

private fun LinkCursor.encode(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString("${createdAt.toEpochMilli()}_${code.value}".toByteArray())

fun kindOf(wire: String): ShareKind? = ShareKind.entries.firstOrNull { it.wire() == wire.trim().lowercase() }

fun ShareKind.wire(): String = name.lowercase()

fun ShareLink.toDto(config: ShareConfig): ShareLinkDto {
    val text = ShareTexts.of(this)
    return ShareLinkDto(
        code = code.value,
        url = config.urlOf(code.value),
        kind = kind.wire(),
        title = text.title,
        summary = text.summary,
        createdAt = createdAt.toString(),
        expiresAt = endsAt?.toString(),
    )
}

fun LinkPage.toResponse(config: ShareConfig) = ShareLinksResponse(links.map { it.toDto(config) }, next?.encode())

fun OpenedLink.toResponse(config: ShareConfig): SharedLinkResponse {
    val dto = link.toDto(config)
    val content = link.content
    return SharedLinkResponse(
        code = dto.code,
        url = dto.url,
        kind = dto.kind,
        title = dto.title,
        summary = dto.summary,
        createdAt = dto.createdAt,
        expiresAt = dto.expiresAt,
        mine = isMine,
        receipt = (content as? ShareContent.Receipt)?.toDto(),
        statement = (content as? ShareContent.Statement)?.toDto(),
        person = (content as? ShareContent.Person)?.let { PersonDto(it.displayName, it.handle) },
        address = (content as? ShareContent.Address)?.let { AddressDto(it.blockchain, it.address, USDC, it.name) },
        open = open?.toDto(),
    )
}

private fun ShareContent.Receipt.toDto() =
    ReceiptDto(
        direction = if (isSent) "sent" else "received",
        amount = amount,
        asset = asset,
        fiat = fiat?.let { FiatAmountDto(it.currency, it.amount.toPlainString()) },
        status = status,
        blockchain = blockchain,
        txHash = txHash,
        explorerUrl = Explorers.transactionUrl(blockchain, txHash),
        at = at.toString(),
        fromName = fromName,
        toName = toName,
    )

private fun ShareContent.Statement.toDto() =
    StatementDto(
        goalName = goalName,
        asset = USDC,
        target = Amounts.usdcText(targetMinor),
        raised = Amounts.usdcText(contributedMinor),
        paidOut = Amounts.usdcText(paidOutMinor),
        held = Amounts.usdcText(heldMinor),
        fiat = fiat?.let { FiatRateDto(it.currency, it.rate.toPlainString()) },
        memberCount = memberCount,
        deadline = deadline?.toString(),
        lines =
            lines.map {
                StatementLineDto(
                    at = it.at.toString(),
                    direction = if (it.isIncoming) "in" else "out",
                    amount = Amounts.usdcText(it.amountMinor),
                    name = it.name,
                    note = it.note,
                )
            },
    )

private fun OpenTarget.toDto(): OpenDto =
    when (this) {
        is OpenTarget.Transfer -> {
            OpenDto("transfer", transferId = transferId.toString())
        }

        is OpenTarget.Chama -> {
            OpenDto("chama", chatId = chatId.toString())
        }

        is OpenTarget.Invite -> {
            OpenDto("invite", userId = userId.toString(), displayName = displayName, inviteToken = inviteToken)
        }

        is OpenTarget.Pay -> {
            OpenDto("pay", userId = userId.toString(), displayName = displayName, handle = handle)
        }

        is OpenTarget.PayAddress -> {
            OpenDto("pay_address", address = address, blockchain = blockchain)
        }
    }

private const val USDC = "USDC"
