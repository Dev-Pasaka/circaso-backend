package com.circaso.share.presentation.mappers

import com.circaso.share.domain.models.ShareContent
import com.circaso.share.domain.models.ShareKind
import com.circaso.share.domain.models.ShareLink
import com.circaso.share.domain.support.Amounts
import com.circaso.share.domain.support.Explorers
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A link's name and one-line summary: the app's list, the page's heading and the link preview in chat apps. */
data class ShareText(val title: String, val summary: String)

/** The copy and the number and date formats every page, preview and answer shares. */
object ShareTexts {
    fun of(link: ShareLink): ShareText =
        when (val content = link.content) {
            is ShareContent.Receipt -> receipt(content)
            is ShareContent.Statement -> statement(content)
            is ShareContent.Person -> person(content, link.kind)
            is ShareContent.Address -> address(content)
            null -> ShareText(TITLE_OFF, "This link was turned off.")
        }

    private fun receipt(r: ShareContent.Receipt): ShareText {
        val coin = "${r.amount} ${r.asset}"
        val amount = r.fiat?.let { "${money(it.currency, it.amount)} ($coin)" } ?: coin
        return ShareText(
            "Payment receipt",
            "$amount · ${statusWords(r.status)} on ${Explorers.chainName(r.blockchain)}",
        )
    }

    private fun statement(s: ShareContent.Statement): ShareText {
        val raised = Amounts.usdcText(s.contributedMinor)
        val target = Amounts.usdcText(s.targetMinor)
        val members = if (s.memberCount == 1) "1 member" else "${s.memberCount} members"
        return ShareText("${s.goalName} statement", "Raised $raised of $target USDC · $members")
    }

    private fun person(p: ShareContent.Person, kind: ShareKind): ShareText =
        if (kind == ShareKind.PAY_ME) {
            ShareText("Pay ${p.displayName} on Circaso", p.handle?.let { "@$it" } ?: "Send money in a chat on Circaso")
        } else {
            ShareText("Chat with ${p.displayName} on Circaso", "${p.displayName} invited you to Circaso")
        }

    private fun address(a: ShareContent.Address): ShareText {
        val chain = Explorers.chainName(a.blockchain)
        val title = a.name?.let { "$it's USDC address on $chain" } ?: "USDC address on $chain"
        return ShareText(title, "Send only USDC on $chain to this address.")
    }

    /** The wallet's states in the words the app uses. */
    fun statusWords(status: String): String =
        when (status) {
            "CONFIRMED" -> "Completed"
            "SUBMITTED" -> "On its way"
            "FAILED" -> "Failed"
            "EXPIRED" -> "Expired"
            else -> "Not signed"
        }

    /** "KES 3,225.00". */
    fun money(currency: String, amount: BigDecimal): String = "$currency ${MONEY.get().format(amount)}"

    /** USDC with thousands separators and at least two decimals: "1,250.50". */
    fun usdc(amount: BigDecimal): String = USDC_FORMAT.get().format(amount)

    /** "2,000.00": a fiat amount without its currency, for pages that set the code apart. */
    fun moneyNumber(amount: BigDecimal): String = MONEY.get().format(amount)

    fun dateTime(at: Instant): String = DATE_TIME.format(at)

    /** "Thursday, 6 Nov 2026": the day line above a chat-style card. */
    fun day(at: Instant): String = DAY.format(at)

    /** "12:41 UTC". */
    fun time(at: Instant): String = TIME.format(at)

    fun date(at: Instant): String = DATE.format(at)

    private const val TITLE_OFF = "Link turned off"

    private val SYMBOLS = DecimalFormatSymbols(Locale.US)
    private val MONEY = ThreadLocal.withInitial { DecimalFormat("#,##0.00", SYMBOLS) }
    private val USDC_FORMAT = ThreadLocal.withInitial { DecimalFormat("#,##0.00####", SYMBOLS) }
    private val DATE_TIME =
        DateTimeFormatter
            .ofPattern(
                "d MMM yyyy, HH:mm 'UTC'",
                Locale.ENGLISH,
            ).withZone(ZoneOffset.UTC)
    private val DAY = DateTimeFormatter.ofPattern("EEEE, d MMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC)
    private val TIME = DateTimeFormatter.ofPattern("HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC)
    private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC)
}
