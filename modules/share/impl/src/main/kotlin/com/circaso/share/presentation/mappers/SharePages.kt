package com.circaso.share.presentation.mappers

import com.circaso.share.domain.models.ShareContent
import com.circaso.share.domain.models.ShareKind
import com.circaso.share.domain.models.ShareLink
import com.circaso.share.domain.models.StatementLine
import com.circaso.share.domain.support.Amounts
import com.circaso.share.domain.support.Explorers
import com.circaso.share.domain.support.ShareConfig
import java.math.BigDecimal
import java.math.RoundingMode

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
 * with its style inline, the Circaso tokens in light and dark, the logo from assets.circaso.com, Open Graph and
 * Twitter tags for the link preview in chat apps, `noindex`, and an "Open in Circaso" / "Get Circaso" pair. Every
 * value that came from a person (a name, a note, a goal) goes through [esc]. The page loads no script.
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
                is ShareContent.Address -> address(content)
                null -> ""
            }
        val actions = actions("circaso://s/${link.code.value}", storeUrl(config, platform), primaryLabel(link.kind))
        return document(
            title = text.title,
            description = text.summary,
            url = url,
            config = config,
            main = body + actions + footer("Shared from Circaso. Only what the sender chose to show is on this page."),
        )
    }

    /** A link its sharer turned off, or that reached the end they set. */
    fun gone(config: ShareConfig, platform: Platform): String =
        notice(
            config,
            platform,
            title = "This link was turned off",
            lead = "The person who shared it turned it off, or it reached the end they set.",
            note = "Ask them to share it again.",
        )

    fun missing(config: ShareConfig, platform: Platform): String =
        notice(
            config,
            platform,
            title = "This link doesn't exist",
            lead = "Check that the whole link was copied. Links are case sensitive.",
            note = null,
        )

    fun throttled(config: ShareConfig, platform: Platform): String =
        notice(
            config,
            platform,
            title = "Too many pages opened",
            lead = "Wait a minute, then open the link again.",
            note = null,
        )

    // ---- the kinds ------------------------------------------------------------------------------------------------

    private fun receipt(r: ShareContent.Receipt): String {
        val direction = if (r.isSent) "Sent" else "Received"
        val coin = "${amountText(r.amount)} ${esc(r.asset)}"
        val fiat = r.fiat?.let { ShareTexts.money(it.currency, it.amount) }
        val explorer = Explorers.transactionUrl(r.blockchain, r.txHash)
        val rows =
            buildList {
                add(row("Status", statusChip(r.status)))
                add(row("Date", esc(ShareTexts.dateTime(r.at))))
                r.fromName?.let { add(row("From", esc(it))) }
                r.toName?.let { add(row("To", esc(it))) }
                add(row("Network", esc(Explorers.chainName(r.blockchain))))
                fiat?.let {
                    add(
                        row("Value", "${esc(it)} <span class=\"muted\">at ${esc(ShareTexts.date(r.fiat.asOf))}</span>"),
                    )
                }
                r.txHash?.let { hash ->
                    val short = esc(shortHash(hash))
                    val cell =
                        explorer?.let { "<a href=\"${esc(it)}\" rel=\"noopener noreferrer\">$short ↗</a>" } ?: short
                    add(row("Transaction", "<span class=\"mono\">$cell</span>"))
                }
            }
        return """
            <p class="eyebrow">Payment receipt</p>
            <section class="money">
              <p class="money-label">$direction</p>
              <p class="money-amount">${fiat?.let(::esc) ?: coin}</p>
              ${if (fiat != null) "<p class=\"money-sub\">$coin</p>" else ""}
            </section>
            ${table(rows)}
            """.trimIndent()
    }

    private fun statement(s: ShareContent.Statement): String {
        val percent =
            if (s.targetMinor <= 0) {
                0
            } else {
                (s.contributedMinor.toDouble() / s.targetMinor * 100).toInt().coerceIn(0, 100)
            }
        val members = if (s.memberCount == 1) "1 member" else "${s.memberCount} members"
        val fiat = s.fiat?.takeIf { it.currency != "USD" }

        fun inFiat(minor: Long): String =
            fiat
                ?.let {
                    " <span class=\"muted\">≈ ${esc(
                        ShareTexts.money(
                            it.currency,
                            Amounts.usdc(minor).multiply(it.rate).setScale(2, RoundingMode.HALF_UP),
                        ),
                    )}</span>"
                }.orEmpty()
        val rows =
            buildList {
                add(row("Raised", "${usdc(s.contributedMinor)} USDC${inFiat(s.contributedMinor)}"))
                add(row("Paid out", "${usdc(s.paidOutMinor)} USDC${inFiat(s.paidOutMinor)}"))
                add(row("In the group wallet", "${usdc(s.heldMinor)} USDC${inFiat(s.heldMinor)}"))
                add(row("Members", esc(members)))
                s.deadline?.let { add(row("Deadline", esc(ShareTexts.date(it)))) }
                add(row("As of", esc(ShareTexts.dateTime(s.asOf))))
            }
        val ledger =
            if (s.lines.isEmpty()) {
                "<p class=\"note\">No money has moved yet.</p>"
            } else {
                "<ul class=\"ledger\">" + s.lines.joinToString("") { line(it) } + "</ul>"
            }
        return """
            <p class="eyebrow">Group statement</p>
            <h1>${esc(s.goalName)}</h1>
            <section class="money">
              <p class="money-label">Raised of ${usdc(s.targetMinor)} USDC</p>
              <p class="money-amount">${usdc(s.contributedMinor)} USDC</p>
              <div class="bar" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="$percent"><span style="width:$percent%"></span></div>
              <p class="money-sub">$percent% of the target</p>
            </section>
            ${table(rows)}
            <h2>Latest activity</h2>
            $ledger
            """.trimIndent()
    }

    private fun line(l: StatementLine): String {
        val who = l.name?.let(::esc) ?: if (l.isIncoming) "Contribution" else "Payout"
        val note = l.note?.let { "<span class=\"line-note\">${esc(it)}</span>" }.orEmpty()
        val sign = if (l.isIncoming) "+" else "−"
        return "<li><span class=\"line-main\"><span class=\"line-who\">$who</span>$note" +
            "<span class=\"line-date\">${esc(ShareTexts.date(l.at))}</span></span>" +
            "<span class=\"line-amount\">$sign${usdc(l.amountMinor)}</span></li>"
    }

    private fun person(p: ShareContent.Person, kind: ShareKind): String {
        val initial =
            esc(
                p.displayName
                    .trim()
                    .take(1)
                    .uppercase()
                    .ifEmpty { "?" },
            )
        val handle = p.handle?.let { "<p class=\"handle\">@${esc(it)}</p>" }.orEmpty()
        val (eyebrow, lead) =
            if (kind == ShareKind.PAY_ME) {
                "Pay on Circaso" to "Send money to ${esc(p.displayName)} in a chat on Circaso. It arrives in seconds."
            } else {
                "Invitation" to
                    "${esc(p.displayName)} invited you to chat on Circaso: messages, calls and money in one place."
            }
        return """
            <p class="eyebrow">$eyebrow</p>
            <section class="person">
              <div class="avatar" aria-hidden="true">$initial</div>
              <h1>${esc(p.displayName)}</h1>
              $handle
            </section>
            <p class="lead">$lead</p>
            """.trimIndent()
    }

    private fun address(a: ShareContent.Address): String {
        val chain = esc(Explorers.chainName(a.blockchain))
        val owner = a.name?.let { "<p class=\"lead\">${esc(it)}'s wallet</p>" }.orEmpty()
        return """
            <p class="eyebrow">USDC address</p>
            <h1>Send USDC on $chain</h1>
            $owner
            <section class="address"><p class="mono select">${esc(a.address)}</p></section>
            <p class="warn">Send only USDC on $chain to this address. Other coins, or USDC on another network, can be lost.</p>
            """.trimIndent()
    }

    // ---- pieces ---------------------------------------------------------------------------------------------------

    private fun notice(
        config: ShareConfig,
        platform: Platform,
        title: String,
        lead: String,
        note: String?,
    ): String =
        document(
            title = title,
            description = "Circaso: chat, calls and money with the people you know.",
            url = null,
            config = config,
            main =
                "<h1>${esc(title)}</h1><p class=\"lead\">${esc(lead)}</p>" +
                    (note?.let { "<p class=\"note\">${esc(it)}</p>" }.orEmpty()) +
                    "<div class=\"actions\"><a class=\"button\" href=\"${esc(
                        storeUrl(config, platform),
                    )}\">Get Circaso</a></div>" +
                    footer("Circaso"),
        )

    private fun primaryLabel(kind: ShareKind): String =
        when (kind) {
            ShareKind.INVITE -> "Chat on Circaso"
            ShareKind.PAY_ME -> "Pay on Circaso"
            else -> "Open in Circaso"
        }

    private fun actions(
        appUrl: String,
        storeUrl: String,
        primary: String,
    ): String =
        """
        <div class="actions">
          <a class="button" href="${esc(appUrl)}">${esc(primary)}</a>
          <a class="button secondary" href="${esc(storeUrl)}">Get Circaso</a>
        </div>
        """.trimIndent()

    private fun footer(text: String) = "<footer>${esc(text)}</footer>"

    private fun storeUrl(config: ShareConfig, platform: Platform): String =
        when (platform) {
            Platform.ANDROID -> config.androidStoreUrl
            Platform.IOS -> config.iosStoreUrl
            Platform.OTHER -> config.websiteUrl
        }

    private fun row(label: String, valueHtml: String) =
        "<tr><th scope=\"row\">${esc(label)}</th><td>$valueHtml</td></tr>"

    private fun table(rows: List<String>) = "<table class=\"facts\">${rows.joinToString("")}</table>"

    private fun statusChip(status: String): String {
        val tone =
            when (status) {
                "CONFIRMED" -> "ok"
                "FAILED", "EXPIRED" -> "bad"
                else -> "wait"
            }
        return "<span class=\"chip $tone\">${esc(ShareTexts.statusWords(status))}</span>"
    }

    private fun usdc(minor: Long): String = esc(ShareTexts.usdc(Amounts.usdc(minor)))

    private fun amountText(amount: String): String =
        amount.toBigDecimalOrNull()?.let { esc(ShareTexts.usdc(it.max(BigDecimal.ZERO))) } ?: esc(amount)

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
        main: String,
    ): String {
        val ogUrl = url?.let { "<meta property=\"og:url\" content=\"${esc(it)}\">" }.orEmpty()
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
            <title>${esc(title)} · Circaso</title>
            <meta name="description" content="${esc(description)}">
            <meta property="og:site_name" content="Circaso">
            <meta property="og:type" content="website">
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
            $STYLE
            </head>
            <body>
            <header class="top">
              <a class="brand" href="${esc(config.websiteUrl)}">
                <picture>
                  <source srcset="$ASSETS/circaso-mark-white-512.png" media="(prefers-color-scheme: dark)">
                  <img src="$ASSETS/circaso-mark-black-512.png" width="24" height="24" alt="">
                </picture>
                <span>Circaso</span>
              </a>
            </header>
            <main>
            $main
            </main>
            </body>
            </html>
            """.trimIndent()
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

    /** The app's tokens (CircasoColors): monochrome, the money card dark in light mode and silver in dark. */
    private val STYLE =
        """
        <style>
          :root {
            --bg: #fff; --grouped: #f2f2f7; --surface: #fff; --fill: #e9e9eb; --sep: #e3e3e8;
            --ink: #0b0b0c; --ink2: #636366; --ink3: #7c7c80;
            --action: #0b0b0c; --on-action: #fff;
            --card: #2b2c30; --sheen: #4a4c52; --on-card: #fff;
            --critical: #c8102e; --warning: #9a5800;
            --r-m: 12px; --r-l: 16px; --r-xl: 22px;
            --font: -apple-system, BlinkMacSystemFont, "SF Pro Text", "Inter", system-ui, "Segoe UI", Roboto, sans-serif;
            --mono: ui-monospace, "SF Mono", Menlo, Consolas, monospace;
            color-scheme: light;
          }
          @media (prefers-color-scheme: dark) {
            :root {
              --bg: #000; --grouped: #000; --surface: #1c1c1e; --fill: #2c2c2e; --sep: #38383a;
              --ink: #f5f5f7; --ink2: #98989f; --ink3: #8e8e93;
              --action: #f5f5f7; --on-action: #0b0b0c;
              --card: #c9cacf; --sheen: #f2f3f6; --on-card: #0b0b0c;
              --critical: #ff5a50; --warning: #ffb340;
              color-scheme: dark;
            }
          }
          * { box-sizing: border-box; }
          html { -webkit-text-size-adjust: 100%; }
          body { margin: 0; background: var(--grouped); color: var(--ink); font: 400 16px/1.5 var(--font); -webkit-font-smoothing: antialiased; }
          a { color: inherit; }
          .top { height: 56px; display: flex; align-items: center; padding: 0 max(16px, env(safe-area-inset-left)); }
          .brand { display: inline-flex; align-items: center; gap: 8px; text-decoration: none; font-weight: 600; letter-spacing: -.01em; }
          .brand img { display: block; }
          main { max-width: 520px; margin: 0 auto; padding: 12px 16px 56px; }
          .eyebrow { margin: 16px 0 8px; font-size: 13px; font-weight: 600; letter-spacing: .04em; text-transform: uppercase; color: var(--ink3); }
          h1 { margin: 0 0 12px; font-size: 30px; line-height: 1.1; letter-spacing: -.02em; font-weight: 700; overflow-wrap: anywhere; }
          h2 { margin: 28px 0 8px; font-size: 13px; font-weight: 600; letter-spacing: .04em; text-transform: uppercase; color: var(--ink3); }
          .lead { margin: 0 0 16px; font-size: 17px; color: var(--ink2); }
          .note { margin: 0 0 16px; font-size: 15px; color: var(--ink2); }
          .muted { color: var(--ink3); font-weight: 400; }
          .mono { font-family: var(--mono); font-size: 14px; }
          .money { margin: 0 0 16px; padding: 22px 20px; border-radius: var(--r-xl); color: var(--on-card);
            background: linear-gradient(135deg, var(--card) 0%, var(--card) 55%, var(--sheen) 100%); }
          .money p { margin: 0; }
          .money-label { font-size: 14px; opacity: .78; }
          .money-amount { margin-top: 4px !important; font-size: 36px; line-height: 1.1; font-weight: 700; letter-spacing: -.02em; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
          .money-sub { margin-top: 6px !important; font-size: 14px; opacity: .78; font-variant-numeric: tabular-nums; }
          .bar { margin-top: 14px; height: 6px; border-radius: 3px; background: color-mix(in srgb, var(--on-card) 22%, transparent); overflow: hidden; }
          .bar span { display: block; height: 100%; border-radius: 3px; background: var(--on-card); }
          .facts { width: 100%; border-collapse: separate; border-spacing: 0; background: var(--surface); border-radius: var(--r-l); overflow: hidden; }
          .facts th, .facts td { padding: 13px 16px; text-align: left; vertical-align: top; border-bottom: 1px solid var(--sep); font-size: 15px; }
          .facts tr:last-child th, .facts tr:last-child td { border-bottom: 0; }
          .facts th { width: 40%; font-weight: 400; color: var(--ink2); }
          .facts td { font-weight: 500; text-align: right; overflow-wrap: anywhere; font-variant-numeric: tabular-nums; }
          .facts a { text-decoration: none; }
          .facts a:hover { text-decoration: underline; }
          .chip { display: inline-block; padding: 3px 10px; border-radius: 999px; font-size: 13px; font-weight: 600; background: var(--fill); }
          .chip.bad { color: var(--critical); }
          .chip.wait { color: var(--warning); }
          .ledger { list-style: none; margin: 0; padding: 0; background: var(--surface); border-radius: var(--r-l); overflow: hidden; }
          .ledger li { display: flex; justify-content: space-between; gap: 12px; padding: 12px 16px; border-bottom: 1px solid var(--sep); }
          .ledger li:last-child { border-bottom: 0; }
          .line-main { display: flex; flex-direction: column; min-width: 0; }
          .line-who { font-weight: 500; overflow-wrap: anywhere; }
          .line-note { font-size: 14px; color: var(--ink2); overflow-wrap: anywhere; }
          .line-date { font-size: 13px; color: var(--ink3); }
          .line-amount { font-weight: 600; white-space: nowrap; font-variant-numeric: tabular-nums; }
          .person { display: flex; flex-direction: column; align-items: center; text-align: center; padding: 28px 16px 8px; }
          .person h1 { margin: 14px 0 2px; }
          .person + .lead { text-align: center; }
          .avatar { width: 88px; height: 88px; border-radius: 50%; display: grid; place-items: center; font-size: 36px; font-weight: 600; background: var(--fill); }
          .handle { margin: 0; color: var(--ink2); font-size: 16px; }
          .address { margin: 0 0 12px; padding: 18px 16px; border-radius: var(--r-l); background: var(--surface); }
          .address p { margin: 0; font-size: 16px; line-height: 1.6; overflow-wrap: anywhere; }
          .select { -webkit-user-select: all; user-select: all; }
          .warn { margin: 0 0 16px; font-size: 14px; color: var(--warning); }
          .actions { display: flex; flex-direction: column; gap: 10px; margin-top: 24px; }
          .button { display: flex; align-items: center; justify-content: center; min-height: 50px; padding: 0 20px; border-radius: 999px;
            background: var(--action); color: var(--on-action); text-decoration: none; font-size: 17px; font-weight: 600; }
          .button.secondary { background: var(--fill); color: var(--ink); }
          .button:focus-visible { outline: 2px solid var(--ink); outline-offset: 3px; }
          footer { margin-top: 32px; font-size: 13px; color: var(--ink3); text-align: center; }
          @media (min-width: 600px) { main { padding-top: 32px; } h1 { font-size: 36px; } }
        </style>
        """.trimIndent()
}
