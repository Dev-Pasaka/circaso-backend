package com.circaso.share.data.mappers

import com.circaso.messaging.api.ChatId
import com.circaso.share.domain.models.FiatRate
import com.circaso.share.domain.models.FiatValue
import com.circaso.share.domain.models.ShareContent
import com.circaso.share.domain.models.StatementLine
import com.circaso.shared.CircasoJson
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * How a snapshot is stored in `share.links.snapshot`: typed JSON, one class per kind, keyed on `type`. Ids and
 * amounts are strings, times epoch milliseconds. Adding a field is fine (old rows decode with its default); renaming
 * one isn't.
 */
@Serializable
internal sealed interface SnapshotJson {
    @Serializable
    @SerialName("receipt")
    data class Receipt(
        val transferId: String,
        val sent: Boolean,
        val amount: String,
        val asset: String,
        val fiat: FiatJson? = null,
        val status: String,
        val blockchain: String,
        val txHash: String? = null,
        val at: Long,
        val fromName: String? = null,
        val toName: String? = null,
    ) : SnapshotJson

    @Serializable
    @SerialName("statement")
    data class Statement(
        val chatId: String,
        val goalName: String,
        val targetMinor: Long,
        val contributedMinor: Long,
        val paidOutMinor: Long,
        val heldMinor: Long,
        val memberCount: Int,
        val deadline: Long? = null,
        val rate: RateJson? = null,
        val lines: List<LineJson> = emptyList(),
        val asOf: Long,
    ) : SnapshotJson

    @Serializable
    @SerialName("person")
    data class Person(
        val userId: String,
        val displayName: String,
        val handle: String? = null,
        val inviteToken: String? = null,
    ) : SnapshotJson

    @Serializable
    @SerialName("address")
    data class Address(val blockchain: String, val address: String, val name: String? = null) : SnapshotJson
}

@Serializable
internal data class FiatJson(val currency: String, val amount: String, val asOf: Long)

@Serializable
internal data class RateJson(val currency: String, val rate: String, val asOf: Long)

@Serializable
internal data class LineJson(
    val at: Long,
    val incoming: Boolean,
    val amountMinor: Long,
    val name: String? = null,
    val note: String? = null,
)

internal fun ShareContent.toJson(): String = CircasoJson.encodeToString(SnapshotJson.serializer(), toSnapshotJson())

/** Null for a row whose snapshot can't be read (written by a newer version), which then opens as gone. */
internal fun String.toContent(): ShareContent? =
    runCatching { CircasoJson.decodeFromString(SnapshotJson.serializer(), this) }.getOrNull()?.toContent()

private fun ShareContent.toSnapshotJson(): SnapshotJson =
    when (this) {
        is ShareContent.Receipt -> {
            SnapshotJson.Receipt(
                transferId = transferId.toString(),
                sent = isSent,
                amount = amount,
                asset = asset,
                fiat = fiat?.let { FiatJson(it.currency, it.amount.toPlainString(), it.asOf.toEpochMilli()) },
                status = status,
                blockchain = blockchain,
                txHash = txHash,
                at = at.toEpochMilli(),
                fromName = fromName,
                toName = toName,
            )
        }

        is ShareContent.Statement -> {
            SnapshotJson.Statement(
                chatId = chatId.toString(),
                goalName = goalName,
                targetMinor = targetMinor,
                contributedMinor = contributedMinor,
                paidOutMinor = paidOutMinor,
                heldMinor = heldMinor,
                memberCount = memberCount,
                deadline = deadline?.toEpochMilli(),
                rate = fiat?.let { RateJson(it.currency, it.rate.toPlainString(), it.asOf.toEpochMilli()) },
                lines = lines.map { LineJson(it.at.toEpochMilli(), it.isIncoming, it.amountMinor, it.name, it.note) },
                asOf = asOf.toEpochMilli(),
            )
        }

        is ShareContent.Person -> {
            SnapshotJson.Person(userId.toString(), displayName, handle, inviteToken)
        }

        is ShareContent.Address -> {
            SnapshotJson.Address(blockchain, address, name)
        }
    }

private fun SnapshotJson.toContent(): ShareContent? =
    when (this) {
        is SnapshotJson.Receipt -> {
            TransferId.parse(transferId)?.let { id ->
                ShareContent.Receipt(
                    transferId = id,
                    isSent = sent,
                    amount = amount,
                    asset = asset,
                    fiat = fiat?.let { FiatValue(it.currency, BigDecimal(it.amount), Instant.ofEpochMilli(it.asOf)) },
                    status = status,
                    blockchain = blockchain,
                    txHash = txHash,
                    at = Instant.ofEpochMilli(at),
                    fromName = fromName,
                    toName = toName,
                )
            }
        }

        is SnapshotJson.Statement -> {
            ChatId.parse(chatId)?.let { id ->
                ShareContent.Statement(
                    chatId = id,
                    goalName = goalName,
                    targetMinor = targetMinor,
                    contributedMinor = contributedMinor,
                    paidOutMinor = paidOutMinor,
                    heldMinor = heldMinor,
                    memberCount = memberCount,
                    deadline = deadline?.let(Instant::ofEpochMilli),
                    fiat = rate?.let { FiatRate(it.currency, BigDecimal(it.rate), Instant.ofEpochMilli(it.asOf)) },
                    lines =
                        lines.map {
                            StatementLine(
                                Instant.ofEpochMilli(it.at),
                                it.incoming,
                                it.amountMinor,
                                it.name,
                                it.note,
                            )
                        },
                    asOf = Instant.ofEpochMilli(asOf),
                )
            }
        }

        is SnapshotJson.Person -> {
            runCatching { UUID.fromString(userId) }.getOrNull()?.let {
                ShareContent.Person(UserId(it), displayName, handle, inviteToken)
            }
        }

        is SnapshotJson.Address -> {
            ShareContent.Address(blockchain, address, name)
        }
    }
