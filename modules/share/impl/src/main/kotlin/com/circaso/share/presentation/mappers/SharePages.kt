package com.circaso.share.presentation.mappers

import com.circaso.share.domain.models.FiatRate
import com.circaso.share.domain.models.ShareContent
import com.circaso.share.domain.models.ShareKind
import com.circaso.share.domain.models.ShareLink
import com.circaso.share.domain.models.StatementLine
import com.circaso.share.domain.support.Amounts
import com.circaso.share.domain.support.Explorers
import com.circaso.share.domain.support.ShareConfig
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** Which store "Get Circaso" points to, from the browser's User-Agent. */
enum class Platform {
    ANDROID,
    IOS,
    OTHER,
    ;

    companion object {
        fun of(userAgent: String?): Platform {
            val ua = userAgent.orEmpty()
            return when {
                ua.contains("Android", ignoreCase = true) -> ANDROID
                IOS_DEVICES.any { ua.contains(it) } -> IOS
                else -> OTHER
            }
        }

        private val IOS_DEVICES = listOf("iPhone", "iPad", "iPod")
    }
}

/**
 * The public page behind a share link, in plain Kotlin (no template library, decision 2026-11-06): one HTML document
 * with its style inline ([SharePageStyle]), the Circaso tokens in light, dark and high contrast, the logo from
 * assets.circaso.com, Open Graph and Twitter tags for the link preview in chat apps, `noindex`, and an
 * "Open in Circaso" / "Get Circaso" pair. Every value that came from a person (a name, a note, a goal) goes through
 * [esc]. The page loads no script: its motion is CSS only and stands down under `prefers-reduced-motion`.
 *
 * The look is the app's chat (redesign 2026-11-07): a payment is the chat's money card (titanium with the money
 * moment when it was received, the quiet fill card when it was sent) with who paid whom above it and its status under
 * it like a message's; a group statement reads as the group's money thread, each line a small money card, with the
 * goal as a ring; an invite is the inviter typing to you.
 */
object SharePages {
    /** What the browser may load: its own inline style and the logo, nothing else. */
    const val CONTENT_SECURITY_POLICY =
        "default-src 'none'; style-src 'unsafe-inline'; img-src https://assets.circaso.com; " +
            "base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

    private const val ASSETS = "https://assets.circaso.com/brand"

    fun live(
        link: ShareLink,
        config: ShareConfig,
        platform: Platform,
    ): String {
        val text = ShareTexts.of(link)
        val url = config.urlOf(link.code.value)
        val body =
            when (val content = link.content) {
                is ShareContent.Receipt -> receipt(content)
                is ShareContent.Statement -> statement(content)
                is ShareContent.Person -> person(content, link.kind)
                is ShareContent.Address -> address(content, platform)
                null -> ""
            }
        return document(
            title = text.title,
            description = text.summary,
            url = url,
            config = config,
            kind = kindLabel(link.kind),
            main = body,
            cta = actions(appUrl(link, config, platform), storeUrl(config, platform), primaryLabel(link.kind)),
            footerHtml = "$LOCK<span>Shared from Circaso. Only what the sender chose to show is on this page.</span>",
        )
    }

    /** A link its sharer turned off ([isRevoked]), or that reached the end they set. */
    fun gone(
        config: ShareConfig,
        platform: Platform,
        isRevoked: Boolean = true,
    ): String =
        if (isRevoked) {
            notice(
                config,
                platform,
                glyph = GLYPH_OFF,
                title = "This link was turned off",
                lead = "The person who shared it turned it off, so what it showed is no longer here.",
                note = "Ask them to share it again.",
            )
        } else {
            notice(
                config,
                platform,
                glyph = GLYPH_ENDED,
                title = "This link has ended",
                lead = "It reached the end its sharer set, so what it showed is no longer here.",
                note = "Ask them to share it again.",
            )
        }

    fun missing(config: ShareConfig, platform: Platform): String =
        notice(
            config,
            platform,
            glyph = GLYPH_MISSING,
            title = "This link doesn't exist",
            lead = "Check that the whole link was copied. Links are case sensitive.",
            note = null,
        )

    fun throttled(config: ShareConfig, platform: Platform): String =
        notice(
            config,
            platform,
            glyph = GLYPH_WAIT,
            title = "Give it a minute",
            lead = "Lots of links were opened from this connection. Try again in a minute.",
            note = null,
        )

    // ---- a payment: who paid whom, the money card, its status like a message's -----------------------------------

    private fun receipt(r: ShareContent.Receipt): String {
        val direction = if (r.isSent) "Sent" else "Received"
        val coinNumber = coinText(r.amount)
        val coin = "${esc(coinNumber)} ${esc(r.asset)}"
        val chain = esc(Explorers.chainName(r.blockchain))
        val hero =
            r.fiat?.let { amount(it.currency, ShareTexts.moneyNumber(it.amount), prefix = true) }
                ?: amount(r.asset, coinNumber, prefix = false)
        val pair =
            if (r.fromName != null || r.toName != null) {
                // The detail rows say From and To in words; this is the picture of it.
                "<section class=\"pair rise\" aria-hidden=\"true\">${party(r.fromName, "From")}" +
                    "<span class=\"flow\" aria-hidden=\"true\"><b></b><i></i>$CHEVRON</span>" +
                    "${party(r.toName, "To")}</section>"
            } else {
                ""
            }
        val details =
            buildList {
                add(detail("Date", esc(ShareTexts.dateTime(r.at))))
                r.fromName?.let { add(detail("From", esc(it))) }
                r.toName?.let { add(detail("To", esc(it))) }
                rate(r)?.let { add(detail("Rate", it)) }
                add(detail("Network", chain))
                r.txHash?.let { add(detail("Transaction", "<span class=\"mono\">${esc(shortHash(it))}</span>")) }
            }
        val explorerRow =
            Explorers
                .transactionUrl(r.blockchain, r.txHash)
                ?.let { href ->
                    val name = Explorers.explorerName(r.blockchain) ?: "the explorer"
                    "<a class=\"row-link\" href=\"${esc(href)}\" rel=\"noopener noreferrer\" target=\"_blank\">" +
                        "<span>View on ${esc(name)}</span>$ARROW_OUT</a>"
                }.orEmpty()
        val foot = if (r.fiat != null) "$coin · $chain" else chain
        return """
            <p class="day rise"><span>${esc(ShareTexts.day(r.at))}</span></p>
            $pair
            <article class="money ${if (r.isSent) "out" else "in"} rise">
              <p class="money-head">${esc(direction)}</p>
              $hero
              <p class="money-foot">$foot</p>
            </article>
            ${status(r.status, ShareTexts.time(r.at))}
            ${if (tone(r.status) == "wait") "<p class=\"status-note rise\">Open the link again to see if it has arrived.</p>" else ""}
            <section class="group rise" aria-label="Details">
              <dl class="details">${details.joinToString("")}</dl>
              $explorerRow
            </section>
            """.trimIndent()
    }

    private fun party(name: String?, role: String): String =
        "<figure class=\"party\"><span class=\"avatar avatar-lg\" aria-hidden=\"true\">${initials(name)}</span>" +
            "<figcaption><span class=\"party-role\">$role</span>" +
            "<span class=\"party-name\">${name?.let { esc(firstName(it)) } ?: "Someone"}</span></figcaption></figure>"

    private fun tone(status: String): String =
        when (status) {
            "CONFIRMED" -> "ok"
            "FAILED", "EXPIRED" -> "bad"
            "SUBMITTED" -> "wait"
            else -> "idle"
        }

    /** The status line under the card, like a message's delivery mark: the check draws itself when it's done. */
    private fun status(status: String, time: String): String {
        val tone = tone(status)
        val icon =
            when (tone) {
                "ok" -> CHECK
                "bad" -> CROSS
                else -> CLOCK
            }
        return "<p class=\"status $tone rise\">$icon<span>${esc(ShareTexts.statusWords(status))}</span>" +
            "<span class=\"status-time\">· ${esc(time)}</span></p>"
    }

    /** "1 USDC = KES 129.03", worked out from the snapshot's own values; null without a fiat value. */
    private fun rate(r: ShareContent.Receipt): String? {
        val fiat = r.fiat ?: return null
        val coin = r.amount.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return null
        val each = fiat.amount.divide(coin, 2, RoundingMode.HALF_UP)
        return esc("1 ${r.asset} = ${ShareTexts.money(fiat.currency, each)}")
    }

    // ---- a group statement: the goal as a ring, then the group's money thread -------------------------------------

    private fun statement(s: ShareContent.Statement): String {
        val percent =
            if (s.targetMinor <= 0) {
                0
            } else {
                (s.contributedMinor.toDouble() / s.targetMinor * 100).toInt().coerceIn(0, 100)
            }
        val members = if (s.memberCount == 1) "1 member" else "${s.memberCount} members"
        val shown: (Long) -> Shown = { minor -> shown(minor, s.fiat) }
        val sub = listOfNotNull(esc(members), s.deadline?.let { "Ends ${esc(ShareTexts.date(it))}" })
        val offset = String.format(Locale.US, "%.2f", RING * (100 - percent) / 100.0)
        val stats =
            listOf("Paid out" to s.paidOutMinor, "In the wallet" to s.heldMinor).joinToString("") { (label, minor) ->
                val money = shown(minor)
                "<div class=\"stat\"><dt>${esc(label)}</dt><dd><span class=\"stat-num\">${esc(money.text)}</span>" +
                    (money.coin?.let { "<span class=\"stat-sub\">${esc(it)}</span>" }.orEmpty()) + "</dd></div>"
            }
        val thread =
            if (s.lines.isEmpty()) {
                "<li class=\"msg sys rise\"><p class=\"bubble ghost\">No money has moved yet.</p></li>"
            } else {
                thread(s.lines, shown)
            }
        val raised = shown(s.contributedMinor)
        val target = shown(s.targetMinor)
        val coinTarget = "${usdc(s.contributedMinor)} of ${usdc(s.targetMinor)} USDC"
        val foot = if (raised.coin != null) "of ${esc(target.text)}<br>$coinTarget" else "of ${usdc(s.targetMinor)} USDC"
        return """
            <section class="group-head rise">
              <span class="avatar avatar-lg group-avatar" aria-hidden="true">${initials(s.goalName)}</span>
              <h1>${esc(s.goalName)}</h1>
              <p class="sub">${sub.joinToString(" · ")}</p>
            </section>
            <article class="money goal rise">
              <div class="ring" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="$percent" aria-valuetext="$percent% of the target">
                <svg viewBox="0 0 120 120" aria-hidden="true"><circle class="ring-track" cx="60" cy="60" r="52"></circle><circle class="ring-fill" cx="60" cy="60" r="52" style="stroke-dashoffset:$offset"></circle></svg>
                <span class="ring-pct" aria-hidden="true">$percent<small>%</small></span>
              </div>
              <div class="goal-text">
                <p class="money-head">Raised</p>
                ${amount(raised.code, raised.number, raised.prefix, size = "amount-m")}
                <p class="money-foot">$foot</p>
              </div>
            </article>
            <dl class="stats rise">$stats</dl>
            <h2 class="section rise">Money in the group</h2>
            <ol class="thread">$thread</ol>
            <p class="asof rise">As of ${esc(ShareTexts.dateTime(s.asOf))}</p>
            """.trimIndent()
    }

    /**
     * A statement amount as the app's money cards show it: the sharer's fiat large ([code] [number], rounded like the
     * cards) with the coin under it ([coin]); without a fiat value, the coin itself large and nothing under it. Never
     * signed: the card's side and words say which way the money went.
     */
    private class Shown(val code: String, val number: String, val prefix: Boolean, val coin: String?) {
        val text: String get() = if (prefix) "$code $number" else "$number $code"
    }

    private fun shown(minor: Long, fiat: FiatRate?): Shown {
        val coin = ShareTexts.usdc(Amounts.usdc(minor))
        return if (fiat == null) {
            Shown("USDC", coin, prefix = false, coin = null)
        } else {
            val number = ShareTexts.fiatRounded(Amounts.fiat(Amounts.usdc(minor), fiat.rate))
            Shown(fiat.currency, number, prefix = true, coin = "$coin USDC")
        }
    }

    /** Oldest first, as a chat reads, with a day line where the day changes. */
    private fun thread(lines: List<StatementLine>, shown: (Long) -> Shown): String {
        var day: LocalDate? = null
        return lines.sortedBy { it.at }.joinToString("") { l ->
            val today = l.at.atZone(ZoneOffset.UTC).toLocalDate()
            val separator =
                if (today != day) {
                    day = today
                    "<li class=\"day rise\"><span>${esc(ShareTexts.date(l.at))}</span></li>"
                } else {
                    ""
                }
            separator + line(l, shown(l.amountMinor))
        }
    }

    /** One line as the chat's money card: money in on titanium from its payer, a payout on the quiet card. */
    private fun line(l: StatementLine, money: Shown): String {
        val who =
            if (l.isIncoming) {
                l.name?.let(::esc) ?: "Contribution"
            } else {
                l.name?.let { "Paid to ${esc(it)}" } ?: "Payout"
            }
        val note = l.note?.let { "<p class=\"money-note\">${esc(it)}</p>" }.orEmpty()
        val foot = listOfNotNull(money.coin?.let(::esc), esc(ShareTexts.time(l.at))).joinToString(" · ")
        val code = "<span class=\"amount-code\">${esc(money.code)}</span>"
        val figure = if (money.prefix) "$code ${esc(money.number)}" else "${esc(money.number)} $code"
        val card =
            "<div class=\"money mini ${if (l.isIncoming) "in" else "out"}\"><p class=\"money-head\">$who</p>" +
                "<p class=\"mini-amount\">$figure</p>$note<p class=\"money-foot\">$foot</p></div>"
        return if (l.isIncoming) {
            "<li class=\"msg in rise\"><span class=\"avatar avatar-sm\" aria-hidden=\"true\">" +
                "${initials(l.name)}</span>$card</li>"
        } else {
            "<li class=\"msg out rise\">$card</li>"
        }
    }

    // ---- a person: to chat with, or to pay ------------------------------------------------------------------------

    private fun person(p: ShareContent.Person, kind: ShareKind): String {
        val name = esc(p.displayName)
        val first = esc(firstName(p.displayName))
        val handle = p.handle?.let { "<p class=\"handle\">@${esc(it)}</p>" }.orEmpty()
        val profile =
            """
            <section class="profile rise">
              <span class="halo" aria-hidden="true"><span class="avatar avatar-xl">${initials(
                p.displayName,
            )}</span></span>
              <h1>$name</h1>
              $handle
            </section>
            """.trimIndent()
        return if (kind == ShareKind.PAY_ME) {
            """
            $profile
            <p class="lead rise">Send money to $name in a chat on Circaso. It arrives in seconds.</p>
            <figure class="how rise">
              <div class="composer" aria-hidden="true">
                <span class="composer-plus">$PLUS</span>
                <span class="composer-field"><span class="hash">#</span><span class="caret"></span><span class="composer-hint">amount</span></span>
                <span class="composer-send">$SEND</span>
              </div>
              <figcaption>In a chat with $first, type <strong>#</strong> and the amount.</figcaption>
            </figure>
            """.trimIndent()
        } else {
            """
            $profile
            <div class="invite rise">
              <span class="avatar avatar-sm" aria-hidden="true">${initials(p.displayName)}</span>
              <div class="invite-stack">
                <span class="typing" aria-hidden="true"><i></i><i></i><i></i></span>
                <p class="bubble invite-bubble">Let's chat on Circaso</p>
              </div>
            </div>
            <p class="lead rise">$name invited you to chat on Circaso: messages, calls and money in one place.</p>
            """.trimIndent()
        }
    }

    // ---- a wallet address -----------------------------------------------------------------------------------------

    private fun address(a: ShareContent.Address, platform: Platform): String {
        val hint =
            if (platform == Platform.OTHER) "Select the address to copy it." else "Press and hold the address to copy it."
        val chain = esc(Explorers.chainName(a.blockchain))
        val owner = a.name?.let { "<p class=\"sub\">${esc(it)}'s wallet</p>" }.orEmpty()
        val groups = a.address.chunked(4).joinToString("") { "<span>${esc(it)}</span>" }
        return """
            <section class="address-head rise">
              <span class="coin" aria-hidden="true">$COIN</span>
              <h1>Send USDC</h1>
              $owner
              <p class="network"><span class="dot" aria-hidden="true"></span>$chain</p>
            </section>
            <article class="address-card rise">
              <p class="address-label">Address</p>
              <p class="sr">${esc(a.address)}</p>
              <p class="address" aria-hidden="true">$groups</p>
              <p class="address-hint">$hint</p>
            </article>
            <aside class="warn rise">$WARN<p>Send only USDC on $chain to this address. Other coins, or USDC on another network, can be lost.</p></aside>
            """.trimIndent()
    }

    // ---- pieces ---------------------------------------------------------------------------------------------------

    private fun notice(
        config: ShareConfig,
        platform: Platform,
        glyph: String,
        title: String,
        lead: String,
        note: String?,
    ): String =
        document(
            title = title,
            description = "Circaso: chat, calls and money with the people you know.",
            url = null,
            config = config,
            kind = null,
            main =
                """
                <section class="empty">
                  <div class="empty-art rise" aria-hidden="true"><span class="empty-bubble">$glyph</span><span class="empty-dot"></span><span class="empty-dot small"></span></div>
                  <h1 class="rise">${esc(title)}</h1>
                  <p class="lead rise">${esc(lead)}</p>
                  ${note?.let { "<p class=\"note rise\">${esc(it)}</p>" }.orEmpty()}
                </section>
                """.trimIndent(),
            cta =
                "<nav class=\"cta\" aria-label=\"Circaso\"><a class=\"button\" href=\"${esc(
                    storeUrl(config, platform),
                )}\">Get Circaso</a></nav>",
            footerHtml = "Circaso: chat, calls and money with the people you know.",
        )

    private fun kindLabel(kind: ShareKind): String =
        when (kind) {
            ShareKind.RECEIPT -> "Receipt"
            ShareKind.STATEMENT -> "Group statement"
            ShareKind.INVITE -> "Invitation"
            ShareKind.PAY_ME -> "Payment request"
            ShareKind.ADDRESS -> "Wallet address"
        }

    private fun primaryLabel(kind: ShareKind): String =
        when (kind) {
            ShareKind.INVITE -> "Chat on Circaso"
            ShareKind.PAY_ME -> "Pay on Circaso"
            else -> "Open in Circaso"
        }

    /** "Open in Circaso" and "Get Circaso"; on a computer, where the app can't open, only the second. */
    private fun actions(
        appUrl: String?,
        storeUrl: String,
        primary: String,
    ): String =
        if (appUrl == null) {
            "<nav class=\"cta\" aria-label=\"Circaso\"><a class=\"button\" href=\"${esc(storeUrl)}\">Get Circaso</a></nav>"
        } else {
            "<nav class=\"cta\" aria-label=\"Open in the app\"><a class=\"button\" href=\"${esc(appUrl)}\">${esc(primary)}</a>" +
                "<a class=\"button secondary\" href=\"${esc(storeUrl)}\">Get Circaso</a></nav>"
        }

    /**
     * Where "Open in Circaso" goes. Android: an intent for the app's `circaso://s/{code}` filter that falls back to
     * the store when the app isn't there, so the first tap never dead-ends. iOS: the scheme (Safari doesn't hand a
     * same-host https link to the app). A computer: nothing, there is no app to open.
     */
    private fun appUrl(link: ShareLink, config: ShareConfig, platform: Platform): String? {
        val code = link.code.value
        return when (platform) {
            Platform.ANDROID -> {
                val fallback = URLEncoder.encode(config.androidStoreUrl, StandardCharsets.UTF_8)
                "intent://s/$code#Intent;scheme=circaso;package=${config.androidPackage};" +
                    "S.browser_fallback_url=$fallback;end"
            }

            Platform.IOS -> "circaso://s/$code"

            Platform.OTHER -> null
        }
    }

    private fun storeUrl(config: ShareConfig, platform: Platform): String =
        when (platform) {
            Platform.ANDROID -> config.androidStoreUrl
            Platform.IOS -> config.iosStoreUrl
            Platform.OTHER -> config.websiteUrl
        }

    private fun detail(label: String, valueHtml: String) =
        "<div class=\"detail\"><dt>${esc(label)}</dt><dd>$valueHtml</dd></div>"

    /**
     * The hero amount: the words once for screen readers, then each character in its own span so the digits can
     * settle in one after another. [prefix] puts the code before the number (fiat), else after it (a coin).
     */
    private fun amount(
        code: String,
        number: String,
        prefix: Boolean,
        size: String? = null,
    ): String {
        val sized =
            size ?: when {
                number.length <= 9 -> "amount-xl"
                number.length <= 13 -> "amount-l"
                else -> "amount-m"
            }
        val digits = number.map { "<span>${esc(it.toString())}</span>" }.joinToString("")
        val codeHtml = "<span class=\"amount-code\">${esc(code)}</span>"
        val spoken = esc(if (prefix) "$code $number" else "$number $code")
        return "<p class=\"amount $sized\"><span class=\"sr\">$spoken</span>" +
            "<span class=\"amount-show\" aria-hidden=\"true\">" +
            (if (prefix) codeHtml else "") + "<span class=\"digits\">$digits</span>" + (if (prefix) "" else codeHtml) +
            "</span></p>"
    }

    private fun initials(name: String?): String {
        if (name.isNullOrBlank()) return PERSON
        val words = name.trim().split(SPACES).filter { w -> w.firstOrNull()?.isLetterOrDigit() == true }
        val letters =
            when {
                words.isEmpty() -> name.trim().take(1)
                words.size == 1 -> words[0].take(1)
                else -> words[0].take(1) + words[1].take(1)
            }
        return esc(letters.uppercase())
    }

    private fun firstName(name: String): String = name.trim().split(SPACES).first()

    private fun usdc(minor: Long): String = esc(ShareTexts.usdc(Amounts.usdc(minor)))

    /** A coin amount as the app shows it ("1,250.50"), unescaped; the raw text if it isn't a number. */
    private fun coinText(amount: String): String =
        amount.toBigDecimalOrNull()?.let { ShareTexts.usdc(it.max(BigDecimal.ZERO)) } ?: amount

    private fun shortHash(hash: String): String =
        if (hash.length <=
            18
        ) {
            hash
        } else {
            hash.take(10) + "…" + hash.takeLast(8)
        }

    private fun document(
        title: String,
        description: String,
        url: String?,
        config: ShareConfig,
        kind: String?,
        main: String,
        cta: String,
        footerHtml: String,
    ): String {
        val ogUrl = url?.let { "<meta property=\"og:url\" content=\"${esc(it)}\">" }.orEmpty()
        val kindChip = kind?.let { "<span class=\"kind\">${esc(it)}</span>" }.orEmpty()
        return """
            <!doctype html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
            <meta name="robots" content="noindex, nofollow">
            <meta name="referrer" content="no-referrer">
            <meta name="color-scheme" content="light dark">
            <meta name="theme-color" content="#ffffff" media="(prefers-color-scheme: light)">
            <meta name="theme-color" content="#000000" media="(prefers-color-scheme: dark)">
            <meta name="format-detection" content="telephone=no">
            <title>${esc(title)} · Circaso</title>
            <meta name="description" content="${esc(description)}">
            <meta property="og:site_name" content="Circaso">
            <meta property="og:type" content="website">
            <meta property="og:locale" content="en_US">
            <meta property="og:title" content="${esc(title)}">
            <meta property="og:description" content="${esc(description)}">
            <meta property="og:image" content="${esc(config.previewImageUrl)}">
            <meta property="og:image:alt" content="Circaso">
            $ogUrl
            <meta name="twitter:card" content="summary">
            <meta name="twitter:title" content="${esc(title)}">
            <meta name="twitter:description" content="${esc(description)}">
            <meta name="twitter:image" content="${esc(config.previewImageUrl)}">
            <link rel="icon" href="$ASSETS/circaso-icon-64.png">
            <link rel="apple-touch-icon" href="$ASSETS/circaso-icon-180.png">
            ${SharePageStyle.STYLE}
            </head>
            <body>
            <div class="ambient" aria-hidden="true"></div>
            <header class="top">
              <a class="brand" href="${esc(config.websiteUrl)}">
                <picture>
                  <source srcset="$ASSETS/circaso-mark-white-512.png" media="(prefers-color-scheme: dark)">
                  <img src="$ASSETS/circaso-mark-black-512.png" width="22" height="22" alt="">
                </picture>
                <span>Circaso</span>
              </a>
              $kindChip
            </header>
            <main class="sheet">
            $main
            $cta
            <footer class="foot">$footerHtml</footer>
            </main>
            </body>
            </html>
            """.trimIndent().trimStart()
    }

    /** Safe inside HTML text and attribute values: control characters dropped, markup characters escaped. */
    fun esc(value: String): String =
        buildString(value.length) {
            value.forEach { c ->
                when {
                    c == '&' -> append("&amp;")
                    c == '<' -> append("&lt;")
                    c == '>' -> append("&gt;")
                    c == '"' -> append("&quot;")
                    c == '\'' -> append("&#39;")
                    c.isISOControl() -> Unit
                    else -> append(c)
                }
            }
        }

    private val SPACES = Regex("\\s+")

    /** The ring's circumference for r = 52. */
    private const val RING = 326.73

    // ---- glyphs: inline SVG in currentColor -----------------------------------------------------------------------

    private const val CHEVRON =
        "<svg class=\"flow-arrow\" width=\"14\" height=\"14\" viewBox=\"0 0 24 24\"><path d=\"M9 5.5l6.5 6.5L9 " +
            "18.5\" " +
            "fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.4\" stroke-linecap=\"round\" " +
            "stroke-linejoin=\"round\"/></svg>"
    private const val CHECK =
        "<svg class=\"status-icon\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" aria-hidden=\"true\">" +
            "<circle cx=\"12\" cy=\"12\" r=\"11\" class=\"status-disc\"/><path class=\"check\" " +
            "d=\"M7 12.4l3.2 3.2L17 8.8\" fill=\"none\" stroke-width=\"2.4\" stroke-linecap=\"round\" " +
            "stroke-linejoin=\"round\"/></svg>"
    private const val CROSS =
        "<svg class=\"status-icon\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" aria-hidden=\"true\">" +
            "<circle cx=\"12\" cy=\"12\" r=\"11\" class=\"status-disc\"/><path class=\"check\" " +
            "d=\"M8.5 8.5l7 7M15.5 8.5l-7 7\" fill=\"none\" stroke-width=\"2.4\" stroke-linecap=\"round\"/></svg>"
    private const val CLOCK =
        "<svg class=\"status-icon\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" aria-hidden=\"true\">" +
            "<circle cx=\"12\" cy=\"12\" r=\"9.5\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\"/>" +
            "<path d=\"M12 7.5V12l3 2\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" " +
            "stroke-linecap=\"round\" stroke-linejoin=\"round\"/></svg>"
    private const val ARROW_OUT =
        "<svg width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" aria-hidden=\"true\"><path d=\"M8 16L16 8M9.5 " +
            "8H16v6.5\" " +
            "fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" " +
            "stroke-linejoin=\"round\"/></svg>"
    private const val PLUS =
        "<svg width=\"16\" height=\"16\" viewBox=\"0 0 24 24\"><path d=\"M12 5v14M5 12h14\" fill=\"none\" " +
            "stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\"/></svg>"
    private const val SEND =
        "<svg width=\"16\" height=\"16\" viewBox=\"0 0 24 24\"><path d=\"M12 19V5M5.5 11.5L12 5l6.5 6.5\" " +
            "fill=\"none\" " +
            "stroke=\"currentColor\" stroke-width=\"2.2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/></svg>"
    private const val LOCK =
        "<svg width=\"12\" height=\"12\" viewBox=\"0 0 24 24\" aria-hidden=\"true\"><path d=\"M7.5 10.5V8a4.5 " +
            "4.5 0 0 1 9 " +
            "0v2.5M6.5 10.5h11a1.5 1.5 0 0 1 1.5 1.5v7a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 19v-7a1.5 1.5 0 0 1 " +
            "1.5-1.5z\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linejoin=\"round\"/></svg>"
    private const val WARN =
        "<svg width=\"20\" height=\"20\" viewBox=\"0 0 24 24\" aria-hidden=\"true\"><path d=\"M12 4l9 16H3z\" " +
            "fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linejoin=\"round\"/>" +
            "<path d=\"M12 10v4.5M12 17.2v.1\" stroke=\"currentColor\" stroke-width=\"2\" " +
            "stroke-linecap=\"round\"/></svg>"
    private const val COIN =
        "<svg width=\"30\" height=\"30\" viewBox=\"0 0 24 24\"><path d=\"M12 5.5v13M15 8.6c-.6-.9-1.6-1.4-3-1.4" +
            "c-1.7 0-2.9.9-2.9 2.1s1.1 1.8 2.9 2.1s3.1 1 3.1 2.3s-1.3 2.1-3.1 2.1c-1.5 0-2.7-.6-3.2-1.6\" " +
            "fill=\"none\" " +
            "stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\"/></svg>"
    private const val PERSON =
        "<svg width=\"55%\" height=\"55%\" viewBox=\"0 0 24 24\"><circle cx=\"12\" cy=\"8.5\" r=\"4\" " +
            "fill=\"currentColor\"/><path d=\"M4.5 20c.8-3.8 3.9-6 7.5-6s6.7 2.2 7.5 6z\" fill=\"currentColor\"/></svg>"
    private const val GLYPH_OFF =
        "<svg width=\"30\" height=\"30\" viewBox=\"0 0 24 24\"><path d=\"M10 14a3.5 3.5 0 0 0 5 0l3-3a3.5 3.5 0 " +
            "0 0-5-5" +
            "l-.8.8M14 10a3.5 3.5 0 0 0-5 0l-3 3a3.5 3.5 0 0 0 5 5l.8-.8M4 4l16 16\" fill=\"none\" " +
            "stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/></svg>"
    private const val GLYPH_ENDED =
        "<svg width=\"30\" height=\"30\" viewBox=\"0 0 24 24\"><path d=\"M7 3.5h10M7 20.5h10M8 3.5c0 4 4 5.5 4 8.5" +
            "s-4 4.5-4 8.5M16 3.5c0 4-4 5.5-4 8.5s4 4.5 4 8.5\" fill=\"none\" stroke=\"currentColor\" " +
            "stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/></svg>"
    private const val GLYPH_MISSING =
        "<svg width=\"30\" height=\"30\" viewBox=\"0 0 24 24\"><circle cx=\"10.5\" cy=\"10.5\" r=\"6\" fill=\"none\" " +
            "stroke=\"currentColor\" stroke-width=\"1.8\"/><path d=\"M15 15l5 5\" stroke=\"currentColor\" " +
            "stroke-width=\"1.8\" stroke-linecap=\"round\"/></svg>"
    private const val GLYPH_WAIT =
        "<svg width=\"30\" height=\"30\" viewBox=\"0 0 24 24\"><circle cx=\"12\" cy=\"12\" r=\"8.5\" fill=\"none\" " +
            "stroke=\"currentColor\" stroke-width=\"1.8\"/><path d=\"M12 7.5V12l3 2\" fill=\"none\" " +
            "stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/></svg>"
}
