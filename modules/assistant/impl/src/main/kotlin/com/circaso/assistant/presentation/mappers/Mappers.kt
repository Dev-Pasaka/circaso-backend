package com.circaso.assistant.presentation.mappers

import com.circaso.assistant.domain.models.AshaVoice
import com.circaso.assistant.domain.models.AskedFrom
import com.circaso.assistant.domain.models.ChatMessage
import com.circaso.assistant.domain.models.ChatPage
import com.circaso.assistant.domain.models.ChatSnippet
import com.circaso.assistant.domain.models.Conversation
import com.circaso.assistant.domain.models.ConversationPage
import com.circaso.assistant.domain.models.DeviceToolResult
import com.circaso.assistant.domain.models.TurnCommand
import com.circaso.assistant.domain.models.TurnResult
import com.circaso.assistant.domain.models.Usage
import com.circaso.assistant.domain.models.VoiceClip
import com.circaso.assistant.domain.support.AshaVoices
import com.circaso.assistant.domain.support.ConversationTitles
import com.circaso.assistant.presentation.dto.ChatMessageDto
import com.circaso.assistant.presentation.dto.ChatResponse
import com.circaso.assistant.presentation.dto.ConversationDto
import com.circaso.assistant.presentation.dto.ConversationsResponse
import com.circaso.assistant.presentation.dto.DoneEvent
import com.circaso.assistant.presentation.dto.ReadNoteDto
import com.circaso.assistant.presentation.dto.SendMessageRequest
import com.circaso.assistant.presentation.dto.ToolResultsRequest
import com.circaso.assistant.presentation.dto.UsageMeterDto
import com.circaso.assistant.presentation.dto.UsageResponse
import com.circaso.assistant.presentation.dto.VoiceDto
import com.circaso.assistant.presentation.dto.VoicesResponse
import com.circaso.shared.FieldError
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency
import java.util.UUID

fun SendMessageRequest.toCommand(
    userId: UserId,
    agentToken: String?,
    requestId: String?,
): Resource<TurnCommand> {
    if (text.isBlank()) return Resource.error(SharedError.Malformed("text", "is required"))
    val code =
        currency?.let {
            displayCurrencyOf(it)
                ?: return Resource.error(SharedError.Malformed("currency", "is not an ISO 4217 currency code"))
        }
    val conversation =
        conversationId?.let {
            parseUuid(it) ?: return Resource.error(SharedError.Malformed("conversationId", "is not valid"))
        }
    return Resource.success(
        TurnCommand(
            userId,
            text,
            askedFrom?.let {
                AskedFrom(it.chatId, it.chatName)
            },
            chatReading,
            agentToken,
            requestId,
            currency = code,
            conversationId = conversation,
        ),
    )
}

/** The ISO 4217 codes the JDK knows. */
private val CURRENCIES: Set<String> = Currency.getAvailableCurrencies().map { it.currencyCode }.toSet()

/** A display currency from the app, in capitals, or null when it isn't an ISO 4217 code. */
fun displayCurrencyOf(raw: String?): String? = raw?.trim()?.uppercase()?.takeIf { it in CURRENCIES }

fun ToolResultsRequest.toCommand(
    userId: UserId,
    turnId: String?,
    agentToken: String?,
    requestId: String?,
): Resource<DeviceToolResult> {
    val turn =
        runCatching { UUID.fromString(turnId) }.getOrNull()
            ?: return Resource.error(SharedError.Malformed("messageId", "is not valid"))
    if (callId.isBlank()) return Resource.error(SharedError.Malformed("callId", "is required"))
    return Resource.success(
        DeviceToolResult(
            userId,
            turn,
            callId,
            snippets.map {
                ChatSnippet(it.chatId, it.chatName, it.sender, it.at, it.text)
            },
            agentToken,
            requestId,
        ),
    )
}

fun ChatMessage.toDto() =
    ChatMessageDto(
        messageId.toString(),
        role.name,
        text,
        replyTo?.toString(),
        askedFrom,
        readNote?.let { ReadNoteDto(it.chatIds, it.chatNames, it.messageCount) },
        createdAt,
        conversationId.toString(),
    )

fun ChatPage.toResponse() = ChatResponse(messages.map { it.toDto() }, nextCursor)

fun Conversation.toDto() =
    ConversationDto(
        conversationId.toString(),
        ConversationTitles.shownFor(this),
        createdAt,
        updatedAt,
        lastMessageAt,
    )

fun ConversationPage.toResponse() = ConversationsResponse(conversations.map { it.toDto() }, nextCursor)

fun TurnResult.Answer.toDoneEvent() = DoneEvent(message.toDto(), conversationId.toString(), conversation?.toDto())

fun Usage.toResponse(): UsageResponse {
    val day = limits.takeUnless { isUnlimited }
    return UsageResponse(
        plan = plan.name,
        textTurnsUsed = textTurnsUsed,
        textTurnsLeft = textTurnsLeft,
        voiceSecondsUsed = voiceSecondsUsed,
        voiceSecondsLeft = voiceSecondsLeft,
        resetsAt = resetsAt,
        periodStart = periodStart,
        periodEnd = resetsAt,
        messages = UsageMeterDto(textTurnsUsed, day?.textTurnsPerDay),
        voiceSeconds = UsageMeterDto(voiceSecondsUsed, day?.voiceSecondsPerDay),
        searches = UsageMeterDto(webSearchesUsed, day?.webSearchesPerDay),
    )
}

fun AshaVoices.toResponse() = VoicesResponse(all.map { it.toDto(isDefault = it.id == default.id) })

fun AshaVoice.toDto(isDefault: Boolean) = VoiceDto(id, name, character, gender.name.lowercase(), isDefault)

fun parseUuid(raw: String?): UUID? = raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }

const val DURATION_HEADER = "X-Duration-Seconds"

/** A clip is sent whole in one request, so it is capped by size as well as by length. */
const val MAX_CLIP_BYTES = 3 * 1024 * 1024

/**
 * A3's clip from its raw body and headers. The length may be a decimal, as a phone's recorder reports it (`5.5`), and
 * is rounded up to whole seconds for the 60 s check and the allowance (decision 2026-10-27). Each problem is named
 * on its own field, so the app can tell a missing length from a clip that is too big.
 */
fun voiceClipOf(
    audio: ByteArray,
    duration: String?,
    contentType: String?,
    language: String?,
): Resource<VoiceClip> {
    val seconds = duration?.trim()?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    val problems =
        listOfNotNull(
            when {
                duration == null -> {
                    FieldError(
                        DURATION_HEADER,
                        "is required: the clip's length in seconds, such as 5 or 5.5",
                    )
                }

                seconds == null -> {
                    FieldError(DURATION_HEADER, "must be a number of seconds above 0, such as 5 or 5.5")
                }

                else -> {
                    null
                }
            },
            FieldError("audio", "must be under 3 MB").takeIf { audio.size > MAX_CLIP_BYTES },
        )
    if (seconds == null || problems.isNotEmpty()) return Resource.error(SharedError.Malformed(problems))

    val wholeSeconds = seconds.setScale(0, RoundingMode.CEILING).min(BigDecimal(Int.MAX_VALUE)).toInt()
    return Resource.success(VoiceClip(audio, contentType ?: "audio/mp4", wholeSeconds, language))
}
