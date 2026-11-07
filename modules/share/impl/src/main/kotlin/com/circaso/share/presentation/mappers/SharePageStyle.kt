package com.circaso.share.presentation.mappers

/**
 * The share pages' one inline stylesheet (CSP: `style-src 'unsafe-inline'`, no external font or file). Colours,
 * radii, type and motion are the app's tokens (`CircasoColors`, the design canvas): light, dark and both high-contrast
 * themes, picked by `prefers-color-scheme` and `prefers-contrast`. Three surfaces on top of them: `--page` (the
 * window), `--panel` (the floating sheet on a wide screen) and `--card` (a grouped block on either).
 *
 * Motion, CSS only and once per open: the blocks rise in on a spring (`linear()`, with a cubic-bezier fallback), the
 * hero amount's characters settle in one after another, a light sweeps the money card once (the app's money moment),
 * the paid check draws itself, the money dot travels from payer to payee, the goal ring fills, the inviter types.
 * Under `prefers-reduced-motion` all of it becomes a short fade, and nothing ever hides text from someone reading.
 */
internal object SharePageStyle {
    /** A spring with damping 0.82 and a 0.5 s response, sampled for CSS `linear()`: about 1% overshoot. */
    private const val SPRING =
        "linear(0, 0.065, 0.209, 0.376, 0.536, 0.673, 0.782, 0.864, 0.922, 0.961, 0.986, 1, 1.008, 1.011, 1.011, " +
            "1.01, 1.008, 1.006, 1.004, 1.003, 1.002, 1.001, 1.001, 1)"

    /** Staggers: the page's blocks 55 ms apart, a thread's messages 45 ms, an amount's characters 28 ms. */
    private val STAGGER =
        (1..14).joinToString(
            "\n",
        ) { ".sheet > :nth-child($it), .empty > :nth-child($it) { --d: ${(it - 1) * 55}ms; }" } +
            "\n" +
            (1..24).joinToString("\n") { ".thread > :nth-child($it) { --d: ${320 + (it - 1) * 45}ms; }" } + "\n" +
            ".thread > :nth-child(n+25) { --d: 1400ms; }\n" +
            (1..20).joinToString("\n") { ".digits > :nth-child($it) { --cd: ${260 + (it - 1) * 28}ms; }" }

    val STYLE: String =
        """
        <style>
          :root {
            --bg: #ffffff; --bg-grouped: #f2f2f7; --surface: #ffffff; --surface-raised: #ffffff;
            --fill: #e9e9eb; --fill-strong: #dcdce0; --separator: #e3e3e8;
            --ink: #0b0b0c; --ink-2: #636366; --ink-3: #7c7c80;
            --action: var(--ink); --on-action: #ffffff;
            --money-card: #2b2c30; --money-sheen: #4a4c52; --on-money: #ffffff;
            --critical: #c8102e; --warning: #9a5800;
            --glass-edge: rgba(0,0,0,0.08);
            --shadow-float: 0 8px 30px rgba(0,0,0,0.10);
            --money-shadow: 0 1px 2px rgba(0,0,0,0.10), 0 18px 40px -14px rgba(20,20,24,0.45);
            --page: var(--bg); --panel: var(--surface); --card: var(--bg-grouped);
            --radius-sm: 10px; --radius-md: 14px; --radius-lg: 22px; --radius-sheet: 34px;
            --hairline: 1px;
            --font-sans: -apple-system, BlinkMacSystemFont, "SF Pro Text", "SF Pro Display", "Inter", "Segoe UI", Roboto, sans-serif;
            --font-rounded: ui-rounded, "SF Pro Rounded", -apple-system, BlinkMacSystemFont, "Inter", "Segoe UI", Roboto, sans-serif;
            --font-mono: ui-monospace, "SF Mono", Menlo, Consolas, "Roboto Mono", monospace;
            --ease-out: cubic-bezier(0.23, 1, 0.32, 1);
            --ease-in-out: cubic-bezier(0.77, 0, 0.175, 1);
            --spring: cubic-bezier(0.23, 1, 0.32, 1);
            color-scheme: light;
          }
          @supports (animation-timing-function: linear(0, 1)) { :root { --spring: $SPRING; } }
          @media (min-resolution: 2dppx) { :root { --hairline: 0.5px; } }
          @media (prefers-color-scheme: dark) {
            :root {
              --bg: #000000; --bg-grouped: #000000; --surface: #1c1c1e; --surface-raised: #2c2c2e;
              --fill: #2c2c2e; --fill-strong: #3a3a3c; --separator: #38383a;
              --ink: #f5f5f7; --ink-2: #98989f; --ink-3: #8e8e93;
              --on-action: #0b0b0c;
              --money-card: #c9cacf; --money-sheen: #f2f3f6; --on-money: #0b0b0c;
              --critical: #ff5a50; --warning: #ffb340;
              --glass-edge: rgba(255,255,255,0.12);
              --shadow-float: 0 8px 30px rgba(0,0,0,0.55);
              --money-shadow: 0 1px 2px rgba(0,0,0,0.5), 0 18px 44px -16px rgba(242,243,246,0.22);
              --card: var(--surface);
              color-scheme: dark;
            }
          }
          @media (prefers-contrast: more) {
            :root {
              --fill: #e4e4e8; --fill-strong: #d1d1d6; --separator: #8a8a8e;
              --ink: #000000; --ink-2: #3a3a3c; --ink-3: #48484a;
              --money-card: #1c1d20; --money-sheen: #3a3c41; --critical: #8c0a1e; --warning: #6e3f00;
              --glass-edge: rgba(0,0,0,0.45);
            }
            .money-head, .money-foot, .amount-code, .ring-pct small { opacity: 1; }
          }
          @media (prefers-contrast: more) and (prefers-color-scheme: dark) {
            :root {
              --fill: #3a3a3c; --fill-strong: #48484a; --separator: #8e8e93;
              --ink: #ffffff; --ink-2: #c7c7cc; --ink-3: #aeaeb2; --on-action: #000000;
              --money-card: #e0e1e5; --money-sheen: #ffffff; --on-money: #000000;
              --critical: #ff928b; --warning: #ffc266; --glass-edge: rgba(255,255,255,0.5);
            }
          }

          * { box-sizing: border-box; }
          html { -webkit-text-size-adjust: 100%; text-size-adjust: 100%; }
          body { margin: 0; min-height: 100vh; min-height: 100dvh; background: var(--page); color: var(--ink);
            font: 400 17px/22px var(--font-sans); letter-spacing: -0.43px; -webkit-font-smoothing: antialiased;
            -moz-osx-font-smoothing: grayscale; text-rendering: optimizeLegibility; -webkit-tap-highlight-color: transparent; }
          p, h1, h2, dl, dd, ol, figure { margin: 0; }
          a { color: inherit; }
          .sr { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); clip-path: inset(50%); white-space: nowrap; }
          .mono { font-family: var(--font-mono); font-size: 14px; letter-spacing: 0; }

          /* The ambient light behind everything: the money card's silver, very soft. */
          .ambient { position: fixed; inset: 0; z-index: -1; pointer-events: none;
            background:
              radial-gradient(70% 38% at 15% -6%, color-mix(in srgb, var(--money-sheen) 16%, transparent), transparent 70%),
              radial-gradient(60% 30% at 100% 0%, color-mix(in srgb, var(--money-card) 7%, transparent), transparent 70%); }

          .top { display: flex; align-items: center; justify-content: space-between; gap: 12px; height: 52px; max-width: 480px;
            margin: 0 auto; padding: 0 max(16px, env(safe-area-inset-right)) 0 max(16px, env(safe-area-inset-left)); }
          .brand { display: inline-flex; align-items: center; gap: 8px; min-height: 44px; padding: 0 4px; margin-left: -4px; text-decoration: none; font: 600 17px/22px var(--font-sans); letter-spacing: -0.43px; }
          .brand img { display: block; }
          .kind { padding: 4px 10px; border-radius: 999px; background: var(--fill); color: var(--ink-2); font: 600 12px/16px var(--font-sans); letter-spacing: 0; white-space: nowrap; }

          .sheet { max-width: 480px; margin: 0 auto; padding: 8px max(16px, env(safe-area-inset-right)) 0 max(16px, env(safe-area-inset-left)); }

          /* ---- the chat's day line and avatars ---- */
          .day { text-align: center; margin: 10px 0 18px; }
          .day span { font: 600 12px/16px var(--font-sans); letter-spacing: 0; color: var(--ink-2); }
          .avatar { flex: none; display: inline-flex; align-items: center; justify-content: center; border-radius: 50%;
            background: var(--fill); color: var(--ink); font-family: var(--font-rounded); font-weight: 600; letter-spacing: 0; overflow: hidden; }
          .avatar-sm { width: 28px; height: 28px; font-size: 11px; }
          .avatar-lg { width: 64px; height: 64px; font-size: 24px; }
          .avatar-xl { width: 96px; height: 96px; font-size: 36px; }

          /* ---- who paid whom ---- */
          .pair { display: flex; align-items: flex-start; justify-content: center; margin: 0 0 22px; }
          .party { width: 104px; display: flex; flex-direction: column; align-items: center; text-align: center; }
          .party figcaption { margin-top: 8px; display: flex; flex-direction: column; min-width: 0; max-width: 100%; }
          .party-role { font: 400 12px/16px var(--font-sans); letter-spacing: 0; color: var(--ink-2); }
          .party-name { font: 600 15px/20px var(--font-sans); letter-spacing: -0.23px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
          .flow { position: relative; flex: 1; max-width: 120px; height: 2px; margin: 31px 0 0; border-radius: 1px;
            background: repeating-linear-gradient(90deg, var(--fill-strong) 0 4px, transparent 4px 8px); color: var(--ink-3); }
          .flow b { position: absolute; inset: 0; border-radius: 1px; background: var(--ink-3); transform-origin: left center; }
          .flow i { position: absolute; inset: 0; opacity: 0; }
          .flow i::after { content: ""; position: absolute; right: -4px; top: -3px; width: 8px; height: 8px; border-radius: 50%; background: var(--ink); }
          .flow-arrow { position: absolute; right: -9px; top: -6px; }

          /* ---- the money card: the chat's, as the page's hero ---- */
          .money { position: relative; overflow: hidden; isolation: isolate; padding: 18px 22px 18px; border-radius: var(--radius-lg) var(--radius-lg) var(--radius-lg) 8px;
            color: var(--on-money); background: linear-gradient(135deg, var(--money-card) 0%, var(--money-card) 55%, var(--money-sheen) 100%);
            box-shadow: var(--money-shadow); }
          .money::before { content: ""; position: absolute; inset: 0; z-index: -1; pointer-events: none;
            background: radial-gradient(120% 70% at 12% 0%, rgba(255,255,255,0.20), transparent 60%); }
          .money::after { content: ""; position: absolute; inset: 0; pointer-events: none;
            background: linear-gradient(105deg, transparent 38%, rgba(255,255,255,0.28) 50%, transparent 62%); transform: translateX(-110%); }
          .money.out { color: var(--ink); background: var(--fill); box-shadow: inset 0 0 0 var(--hairline) var(--glass-edge);
            border-radius: var(--radius-lg) var(--radius-lg) 8px var(--radius-lg); }
          .money.out::before, .money.out::after { display: none; }
          .money.out .money-head, .money.out .money-foot, .money.out .amount-code { color: var(--ink-2); opacity: 1; }
          .money.mini { width: 264px; max-width: 80%; padding: 16px 20px; display: flex; flex-direction: column; gap: 4px; box-shadow: none; }
          .money.mini::after { display: none; }
          .msg.in .money.mini { border-radius: var(--radius-lg) var(--radius-lg) var(--radius-lg) 8px; }
          .money.mini.out { box-shadow: inset 0 0 0 var(--hairline) var(--glass-edge); }
          .mini-amount { font: 600 28px/32px var(--font-rounded); letter-spacing: -0.3px; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
          .mini-amount .amount-code { font-size: 17px; line-height: 22px; }
          .money-note { font: 400 16px/21px var(--font-sans); letter-spacing: -0.31px; overflow-wrap: anywhere; }
          .money-head { font: 600 13px/18px var(--font-sans); letter-spacing: -0.08px; opacity: 0.82; }
          .money-foot { font: 400 13px/18px var(--font-sans); letter-spacing: -0.08px; opacity: 0.82; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
          .amount { margin: 4px 0 6px; font-family: var(--font-rounded); font-weight: 600; font-variant-numeric: tabular-nums; }
          .amount-show { display: flex; flex-wrap: wrap; align-items: baseline; gap: 0 6px; }
          .digits { white-space: nowrap; }
          .digits > span { display: inline-block; }
          .amount-code { opacity: 0.82; font-weight: 600; letter-spacing: 0; }
          .amount-xl { font-size: 56px; line-height: 60px; letter-spacing: -0.8px; }
          .amount-xl .amount-code { font-size: 22px; line-height: 28px; }
          .amount-l { font-size: 40px; line-height: 44px; letter-spacing: -0.5px; }
          .amount-l .amount-code { font-size: 20px; line-height: 25px; }
          .amount-m { font-size: 28px; line-height: 32px; letter-spacing: -0.3px; }
          .amount-m .amount-code { font-size: 17px; line-height: 22px; }

          /* ---- the status under the card, like a message's ---- */
          .status { display: flex; align-items: center; gap: 6px; margin: 10px 6px 26px; font: 400 13px/18px var(--font-sans); letter-spacing: -0.08px; color: var(--ink-2); }
          .status > span:first-of-type { font-weight: 600; color: var(--ink); }
          .status-time { font-variant-numeric: tabular-nums; }
          .status-icon { flex: none; overflow: visible; }
          .status-disc { fill: var(--ink); transform-box: fill-box; transform-origin: center; }
          .check { stroke: var(--on-action); stroke-dasharray: 20; stroke-dashoffset: 0; }
          .status.bad { color: var(--critical); }
          .status.bad > span:first-of-type { color: var(--critical); }
          .status.bad .status-disc { fill: var(--critical); }
          .status.wait .status-icon { color: var(--warning); }
          .status.idle .status-icon { color: var(--ink-3); }
          .status-note { margin: -18px 6px 26px; font: 400 13px/18px var(--font-sans); letter-spacing: -0.08px; color: var(--ink-2); }

          /* ---- grouped details, as C7 ---- */
          .group { border-radius: var(--radius-md); background: var(--card); overflow: hidden; }
          .details { display: flex; flex-direction: column; }
          .detail { display: flex; justify-content: space-between; gap: 12px; padding: 12px 16px; font: 400 15px/20px var(--font-sans); letter-spacing: -0.23px; }
          .detail + .detail { box-shadow: inset 0 var(--hairline) 0 var(--separator); }
          .detail dt { color: var(--ink-2); flex: none; }
          .detail dd { font-weight: 600; text-align: right; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; min-width: 0; }
          .row-link { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 48px; padding: 12px 16px;
            box-shadow: inset 0 var(--hairline) 0 var(--separator); text-decoration: none; font: 600 15px/20px var(--font-sans); letter-spacing: -0.23px; }
          .row-link svg { color: var(--ink-2); }

          /* ---- a group statement ---- */
          .group-head { display: flex; flex-direction: column; align-items: center; text-align: center; padding: 8px 0 22px; }
          .group-avatar { background: var(--action); color: var(--on-action); }
          .group-head h1 { margin-top: 12px; font: 700 28px/34px var(--font-sans); letter-spacing: 0.36px; overflow-wrap: anywhere; }
          .sub { margin-top: 4px; font: 400 15px/20px var(--font-sans); letter-spacing: -0.23px; color: var(--ink-2); }
          .goal { display: flex; align-items: center; gap: 18px; padding: 18px 20px; }
          .ring { position: relative; flex: none; width: 104px; height: 104px; }
          .ring svg { width: 100%; height: 100%; transform: rotate(-90deg); }
          .ring circle { fill: none; stroke-width: 10; }
          .ring-track { stroke: currentColor; opacity: 0.18; }
          .ring-fill { stroke: currentColor; stroke-linecap: round; stroke-dasharray: 326.73; }
          .ring-pct { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; font: 600 28px/32px var(--font-rounded); letter-spacing: -0.3px; font-variant-numeric: tabular-nums; }
          .ring-pct small { font-size: 15px; opacity: 0.82; margin-left: 1px; }
          .goal-text { min-width: 0; }
          .goal .amount { margin: 2px 0 4px; }
          .stats { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; margin: 12px 0 28px; }
          .stat { padding: 12px; border-radius: var(--radius-md); background: var(--card); display: flex; flex-direction: column; gap: 4px; }
          .stat dt { font: 400 12px/16px var(--font-sans); letter-spacing: 0; color: var(--ink-2); }
          .stat dd { display: flex; flex-direction: column; gap: 2px; }
          .stat-num { font: 600 15px/20px var(--font-rounded); letter-spacing: -0.23px; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
          .stat-sub { font: 400 12px/16px var(--font-sans); letter-spacing: 0; color: var(--ink-2); font-variant-numeric: tabular-nums; }
          .section { margin: 0 4px 6px; font: 600 13px/18px var(--font-sans); letter-spacing: -0.08px; color: var(--ink-2); }
          .thread { list-style: none; padding: 0; display: flex; flex-direction: column; gap: 6px; }
          .thread .day { margin: 10px 0 4px; }
          .msg { display: flex; align-items: flex-end; gap: 8px; }
          .msg.out { justify-content: flex-end; }
          .msg.sys { justify-content: center; }
          .bubble { max-width: 78%; padding: 9px 14px 7px; border-radius: var(--radius-lg); background: var(--fill); color: var(--ink); overflow-wrap: anywhere; }
          .msg.in .bubble { border-bottom-left-radius: 8px; }
          .msg.out .bubble-who, .msg.out .bubble-sub, .msg.out .bubble-time { color: inherit; opacity: 0.72; }
          .bubble.ghost { max-width: none; padding: 14px 18px; background: transparent; border: 1px dashed var(--fill-strong); color: var(--ink-2); font: 400 15px/20px var(--font-sans); letter-spacing: -0.23px; text-align: center; }
          .asof { margin: 18px 0 0; text-align: center; font: 400 12px/16px var(--font-sans); letter-spacing: 0; color: var(--ink-2); font-variant-numeric: tabular-nums; }

          /* ---- a person ---- */
          .profile { display: flex; flex-direction: column; align-items: center; text-align: center; padding: 28px 0 4px; }
          .halo { position: relative; display: inline-flex; margin-bottom: 18px; }
          .halo::before, .halo::after { content: ""; position: absolute; border-radius: 50%; pointer-events: none; }
          .halo::before { inset: -10px; box-shadow: 0 0 0 1px var(--separator); }
          .halo::after { inset: -24px; box-shadow: 0 0 0 1px color-mix(in srgb, var(--separator) 55%, transparent); }
          .halo .avatar { position: relative; color: var(--on-money); box-shadow: var(--money-shadow);
            background: linear-gradient(135deg, var(--money-card) 0%, var(--money-card) 50%, var(--money-sheen) 100%); }
          .profile h1 { font: 700 28px/34px var(--font-sans); letter-spacing: 0.36px; overflow-wrap: anywhere; }
          .handle { margin-top: 2px; font: 400 17px/22px var(--font-sans); color: var(--ink-2); overflow-wrap: anywhere; }
          .lead { margin: 18px auto 0; max-width: 340px; text-align: center; font: 400 17px/24px var(--font-sans); letter-spacing: -0.43px; color: var(--ink-2); }
          .note { margin: 10px auto 0; max-width: 340px; text-align: center; font: 400 15px/20px var(--font-sans); letter-spacing: -0.23px; color: var(--ink-2); }
          .how { margin: 26px auto 0; max-width: 340px; }
          .composer { display: flex; align-items: center; gap: 8px; padding: 6px; border-radius: 999px; background: var(--card); box-shadow: inset 0 0 0 var(--hairline) var(--glass-edge); }
          .composer-plus, .composer-send { flex: none; width: 34px; height: 34px; border-radius: 50%; display: grid; place-items: center; }
          .composer-plus { color: var(--ink-2); }
          .composer-send { background: var(--action); color: var(--on-action); }
          .composer-field { flex: 1; display: flex; align-items: center; min-height: 34px; padding: 0 4px; font: 400 17px/22px var(--font-sans); }
          .hash { font: 600 20px/22px var(--font-rounded); }
          .caret { width: 2px; height: 20px; margin: 0 2px 0 1px; border-radius: 1px; background: var(--ink); }
          .composer-hint { color: var(--ink-2); }
          .how figcaption { margin-top: 10px; text-align: center; font: 400 13px/18px var(--font-sans); letter-spacing: -0.08px; color: var(--ink-2); }
          .how strong { font-family: var(--font-rounded); color: var(--ink); }
          .invite { display: flex; align-items: flex-end; gap: 8px; width: fit-content; margin: 26px auto 0; }
          .invite-stack { display: grid; }
          .invite-stack > * { grid-area: 1 / 1; }
          .typing { align-self: end; justify-self: start; display: inline-flex; gap: 4px; padding: 13px 14px; border-radius: var(--radius-lg) var(--radius-lg) var(--radius-lg) 8px; background: var(--fill); opacity: 0; }
          .typing i { width: 7px; height: 7px; border-radius: 50%; background: var(--ink-3); }
          .invite-bubble { max-width: none; border-bottom-left-radius: 8px; padding: 9px 14px; font: 400 17px/22px var(--font-sans); letter-spacing: -0.43px; transform-origin: 0 100%; }

          /* ---- a wallet address ---- */
          .address-head { display: flex; flex-direction: column; align-items: center; text-align: center; padding: 20px 0 22px; }
          .coin { width: 64px; height: 64px; border-radius: 50%; display: grid; place-items: center; margin-bottom: 14px; color: var(--on-money); box-shadow: var(--money-shadow);
            background: linear-gradient(135deg, var(--money-card) 0%, var(--money-card) 50%, var(--money-sheen) 100%); }
          .address-head h1 { font: 700 28px/34px var(--font-sans); letter-spacing: 0.36px; }
          .network { display: inline-flex; align-items: center; gap: 6px; margin-top: 10px; padding: 5px 12px; border-radius: 999px; background: var(--fill); font: 600 13px/18px var(--font-sans); letter-spacing: -0.08px; }
          .network .dot { width: 6px; height: 6px; border-radius: 50%; background: var(--ink); }
          .address-card { padding: 16px 18px 14px; border-radius: var(--radius-lg); background: var(--card); }
          .address-label { font: 600 12px/16px var(--font-sans); letter-spacing: 0; color: var(--ink-2); }
          .address { margin-top: 6px; font: 500 19px/30px var(--font-mono); letter-spacing: 0; -webkit-user-select: all; user-select: all; cursor: text; }
          .address span { display: inline-block; margin-right: 0.42em; }
          .address span:nth-child(even) { color: var(--ink-2); }
          .address-hint { margin-top: 8px; font: 400 12px/16px var(--font-sans); letter-spacing: 0; color: var(--ink-2); }
          .warn { display: flex; gap: 10px; align-items: flex-start; margin-top: 12px; padding: 12px 14px; border-radius: var(--radius-md);
            background: var(--fill); background: color-mix(in srgb, var(--warning) 10%, transparent); color: var(--warning); }
          .warn svg { flex: none; margin-top: 0; }
          .warn p { font: 400 15px/20px var(--font-sans); letter-spacing: -0.23px; }

          /* ---- a link that isn't there ---- */
          .empty { display: flex; flex-direction: column; align-items: center; text-align: center; padding: 56px 0 8px; }
          .empty h1 { font: 700 28px/34px var(--font-sans); letter-spacing: 0.36px; }
          .empty .lead { margin-top: 10px; }
          .empty-art { position: relative; width: 116px; height: 100px; margin-bottom: 18px; }
          .empty-bubble { position: absolute; left: 18px; top: 0; width: 88px; height: 72px; display: grid; place-items: center;
            border-radius: 28px 28px 28px 10px; background: var(--card); color: var(--ink-2); box-shadow: inset 0 0 0 var(--hairline) var(--glass-edge); }
          .empty-dot { position: absolute; left: 8px; top: 74px; width: 14px; height: 14px; border-radius: 50%; background: var(--card); box-shadow: inset 0 0 0 var(--hairline) var(--glass-edge); }
          .empty-dot.small { left: 0; top: 90px; width: 8px; height: 8px; }

          /* ---- the way into the app ---- */
          .cta { position: sticky; bottom: 0; z-index: 2; display: flex; gap: 10px; margin: 28px -16px 0; padding: 18px 16px calc(14px + env(safe-area-inset-bottom));
            background: var(--page);
            background: linear-gradient(to bottom, color-mix(in srgb, var(--page) 0%, transparent), var(--page) 26px); }
          .button { flex: 1 1 0; display: flex; align-items: center; justify-content: center; min-height: 50px; padding: 0 18px; border-radius: 999px;
            background: var(--action); color: var(--on-action); text-decoration: none; font: 600 17px/22px var(--font-sans); letter-spacing: -0.43px;
            white-space: nowrap; transition: transform 120ms var(--ease-out), background-color 120ms ease; }
          .button:first-child { flex-grow: 1.5; }
          .button.secondary { background: var(--fill); color: var(--ink); }
          .button:active { transform: scale(0.97); }
          .button.secondary:active { background: var(--fill-strong); }
          .button:focus-visible, .row-link:focus-visible, .brand:focus-visible { outline: 2px solid var(--ink); outline-offset: 3px; }
          .foot { margin: 4px auto 0; max-width: 340px; padding: 0 0 calc(24px + env(safe-area-inset-bottom));
            text-align: center; font: 400 12px/16px var(--font-sans); letter-spacing: 0; color: var(--ink-2); text-wrap: balance; }
          .foot svg { display: inline-block; vertical-align: -1px; margin-right: 5px; }

          /* ---- wide screens: the page floats as a sheet over the ambient light ---- */
          @media (min-width: 720px) {
            :root { --page: var(--bg-grouped); --card: var(--bg-grouped); }
            .top { padding-top: 8px; height: 68px; }
            .sheet { margin: 4px auto 40px; max-width: 480px; padding: 28px 32px 0; border-radius: var(--radius-sheet); background: var(--panel);
              box-shadow: 0 0 0 var(--hairline) var(--glass-edge), var(--shadow-float); }
            .cta { margin: 28px -32px 0; padding: 20px 32px 18px; background: var(--panel); background: linear-gradient(to bottom, color-mix(in srgb, var(--panel) 0%, transparent), var(--panel) 22px); }
            .foot { padding: 0 0 24px; }
            .empty { padding-top: 40px; }
          }
          @media (min-width: 720px) and (prefers-color-scheme: dark) {
            :root { --card: var(--surface-raised); }
            .sheet { --fill: var(--fill-strong); }
          }
          @media (hover: hover) and (pointer: fine) {
            .button:hover { filter: brightness(1.08); }
            .row-link:hover span { text-decoration: underline; text-underline-offset: 3px; }
          }

          /* ---- motion ---- */
          @keyframes rise { from { transform: translateY(14px) scale(0.985); } }
          @keyframes fade { from { opacity: 0; } }
          @keyframes settle { from { transform: translateY(0.3em); } }
          @keyframes sweep { to { transform: translateX(110%); } }
          @keyframes draw { from { stroke-dashoffset: 20; } }
          @keyframes pop { from { transform: scale(0.4); } }
          @keyframes line { from { transform: scaleX(0); } }
          @keyframes travel { 0% { transform: translateX(-100%); opacity: 0; } 12%, 80% { opacity: 1; } 100% { transform: translateX(0); opacity: 0; } }
          @keyframes ring { from { stroke-dashoffset: 326.73; } }
          @keyframes halo { from { transform: scale(0.82); opacity: 0; } }
          @keyframes typing { 0% { opacity: 0; transform: scale(0.9); } 12%, 82% { opacity: 1; transform: none; } 100% { opacity: 0; transform: scale(0.96); } }
          @keyframes dot { 0%, 60%, 100% { transform: none; opacity: 0.4; } 30% { transform: translateY(-3px); opacity: 1; } }
          @keyframes bubble { from { opacity: 0; transform: translateY(6px) scale(0.92); } }
          @keyframes blink { 50% { opacity: 0; } }

          .rise { animation: rise 900ms var(--spring) both, fade 360ms var(--ease-out) both; animation-delay: var(--d, 0ms); }
          .digits > span { animation-name: settle, fade; animation-duration: 760ms, 380ms; animation-timing-function: var(--spring), var(--ease-out); animation-fill-mode: both; animation-delay: var(--cd, 0ms); }
          .money::after { animation: sweep 900ms cubic-bezier(0.2, 0.7, 0.2, 1) 650ms 1 forwards; }
          .status.ok .status-disc, .status.bad .status-disc { animation: pop 620ms var(--spring) 520ms both; }
          .status.ok .check, .status.bad .check { animation: draw 380ms var(--ease-out) 760ms both; }
          .flow b { animation: line 760ms var(--ease-in-out) 380ms both; }
          .flow i { animation: travel 760ms var(--ease-in-out) 380ms both; }
          .ring-fill { animation: ring 1200ms var(--ease-out) 380ms both; }
          .halo::before { animation: halo 900ms var(--spring) 180ms both; }
          .halo::after { animation: halo 900ms var(--spring) 280ms both; }
          .typing { animation: typing 900ms var(--ease-out) 200ms both; }
          .typing i { animation: dot 600ms ease-in-out 1; animation-delay: 250ms; }
          .typing i:nth-child(2) { animation-delay: 350ms; }
          .typing i:nth-child(3) { animation-delay: 450ms; }
          .invite-bubble { animation: bubble 620ms var(--spring) 950ms both; }
          .caret { animation: blink 1.1s step-end infinite; }
        $STAGGER

          @media (prefers-reduced-motion: reduce) {
            .rise, .digits > span { animation: fade 200ms ease-out both; }
            .money::after, .flow i, .typing { display: none; }
            .status .status-disc, .status .check, .flow b, .ring-fill, .halo::before, .halo::after, .caret { animation: none; }
            .invite-bubble { animation: fade 200ms ease-out both; }
          }
        </style>
        """.trimIndent()
}
