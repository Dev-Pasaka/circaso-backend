// Builds the Resend templates into this folder: `bun run build.ts`. Each .html file is self-contained, so it can be
// copied whole and pasted into Resend. The layout lives here once so the emails stay identical.
//
// Email HTML is not web HTML: layout is tables, styles are inline (the <style> block only adds dark mode and the
// phone layout, for the clients that read it), the only images are the logo and the Asha icon (hosted on
// assets.circaso.com), and there is no glass, blur or variable fonts, which mail clients ignore. The design carries
// over circaso.com's tokens: ink on white, grouped grey panels, hairline borders, the system font with tight
// tracking on headings, and a black pill button.
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';

const ASSETS = 'https://assets.circaso.com/brand'; // the circaso-static R2 bucket (public, assets.circaso.com)
const SUPPORT = 'support@circaso.com'; // TODO(decision: the address people reply to for security help)
const MAX_BYTES = 90_000; // Gmail clips mail over about 102 KB

// ---------------------------------------------------------------------------------------------------------------
// Tokens
// ---------------------------------------------------------------------------------------------------------------

const FONT = "-apple-system, BlinkMacSystemFont, 'SF Pro Text', 'Helvetica Neue', Helvetica, Arial, sans-serif";

const color = {
  ink: '#0b0b0c',
  white: '#ffffff',
  muted: '#636366',
  faint: '#7c7c80',
  hairline: '#e3e3e8',
  panel: '#f2f2f7',
};

/** Attributes every layout table carries, because mail clients add their own spacing without them. */
const TABLE = 'role="presentation" cellpadding="0" cellspacing="0" border="0"';

/** An inline style from its declarations, so each one sits on its own line instead of in a 200-character string. */
const style = (...declarations: string[]) => declarations.join(';');

/** The `font` shorthand: weight, size, line height (a ratio, or pixels as `'40px'`), then the family. */
const font = (weight: number, size: number, leading: number | string) =>
  `font:${weight} ${size}px/${leading} ${FONT}`;

/** Text styles used by more than one piece. */
const text = {
  label: (size: number, leading = 1.3) =>
    style(font(500, size, leading), 'letter-spacing:.04em', 'text-transform:uppercase', `color:${color.muted}`),
  rowTitle: (weight: number, size: number, tracking: string) =>
    style(font(weight, size, 1.3), `letter-spacing:${tracking}`, `color:${color.ink}`),
  rowText: (margin: string, size: number, leading = 1.5) =>
    style(margin, font(400, size, leading), `color:${color.muted}`),
};

// ---------------------------------------------------------------------------------------------------------------
// What each email says
// ---------------------------------------------------------------------------------------------------------------

interface Mail {
  file: string;
  subject: string;
  preheader: string;
  eyebrow: string;
  title: string;
  /** Paragraphs shown above the panel. */
  intro: string[];
  /** The key facts (label, value), shown in a grey panel. */
  facts?: [string, string][];
  /** Paragraphs after the panel. */
  outro: string[];
  /** Numbered first steps (title, one line, optional icon file), shown after the intro. */
  steps?: [string, string, string?][];
  /** The "not you?" box. */
  notYou?: { heading: string; text: string; button: string };
  /** Resend template variables the email uses; the build fails if this list and the HTML disagree. */
  variables: string[];
}

const mails: Mail[] = [
  {
    file: 'welcome.html',
    subject: 'Welcome to Circaso',
    preheader: 'Your account is ready. Three things to try first.',
    eyebrow: 'Welcome',
    title: 'Welcome to Circaso',
    intro: [
      'Your account is ready. Circaso is where you talk to the people you know and send, receive and save money ' +
        'with them, without leaving the conversation.',
    ],
    steps: [
      ['Add your contacts', 'Circaso shows you who you already know here, and lets you invite the rest.'],
      ['Claim your handle', 'A short name people can use to find you, so you never have to share your number.'],
      [
        'Ask Asha',
        'Asha is the assistant in your chat. Ask a question, or ask it to help with something.',
        'asha/asha-icon-silver-96.png',
      ],
    ],
    outro: ['Questions? Just reply to this email and a person will answer.'],
    variables: [],
  },
  {
    file: 'new-phone-sign-in.html',
    subject: 'New phone signed in to your Circaso account',
    preheader: 'Your Circaso account was opened on a new phone. If that was you, there is nothing to do.',
    eyebrow: 'Security notice',
    title: 'Your account was opened on a new phone',
    intro: [
      'Someone signed in to your Circaso account on a phone we have not seen before, ' +
        'and your account now works on that phone.',
    ],
    facts: [['Phone', '{{{DEVICE_MODEL}}}']],
    outro: [
      'If this was you, there is nothing to do. ' +
        'Circaso signs you out of your old phone, so it will ask you to sign in again.',
    ],
    notYou: {
      heading: 'Not you?',
      text:
        'Open Circaso on a phone you trust, review your signed-in devices under Account and security, ' +
        'and tell us straight away so we can protect your account.',
      button: 'Contact support',
    },
    variables: ['DEVICE_MODEL'],
  },
  {
    file: 'pin-vault-opened.html',
    subject: 'Your Circaso PIN backup was opened',
    preheader: 'Your PIN backup was opened on your phone. Payments are paused for 24 hours to keep your money safe.',
    eyebrow: 'Security notice',
    title: 'Your PIN backup was opened',
    intro: [
      'Your wallet PIN backup (your PIN Vault) was just opened on your phone, with your passkey, ' +
        'to restore your PIN.',
      'To keep your money safe, payments from your wallet are paused for 24 hours. ' +
        'You can still receive money and use the rest of Circaso as usual.',
    ],
    facts: [['Phone', '{{{DEVICE_MODEL}}}']],
    outro: ['If this was you, there is nothing to do. Payments start again on their own after 24 hours.'],
    notYou: {
      heading: 'Not you?',
      text:
        'Someone may have your phone and your passkey. Contact us straight away so we can protect your account. ' +
        'Do not share codes or your PIN with anyone, including people who say they are from Circaso.',
      button: 'Contact support',
    },
    variables: ['DEVICE_MODEL'],
  },
  {
    file: 'recovery-started.html',
    subject: 'A request was made to recover your Circaso account',
    preheader: 'Someone asked to change how you sign in to Circaso. Nothing changes until it is verified.',
    eyebrow: 'Security notice',
    title: 'Someone asked to recover your account',
    intro: [
      'A request was made to change how you sign in to Circaso, or to move your phone number to a different account.',
      'Our team checks every request before anything changes. Your account, your money and your messages stay as ' +
        'they are until then, and you will get another email before any change takes effect.',
    ],
    outro: ['If you made this request, you do not need to do anything.'],
    notYou: {
      heading: 'Did not ask for this?',
      text:
        'Tell us now. We will stop the request and help you secure your account. ' +
        'Do not share codes or your PIN with anyone, including people who say they are from Circaso.',
      button: 'Contact support',
    },
    variables: [],
  },
  {
    file: 'recovery-completed.html',
    subject: 'Your Circaso account recovery is complete',
    preheader: 'Your account now signs in the new way. Your money and messages are unchanged.',
    eyebrow: 'Security notice',
    title: 'Your account recovery is complete',
    intro: [
      'We finished the recovery you asked for. ' +
        'Your account now signs in the new way, and the old sign-in no longer works.',
      'Your money, messages and contacts are exactly as you left them.',
    ],
    outro: ['You can sign in to Circaso now.'],
    notYou: {
      heading: 'Not you?',
      text: 'If you did not ask for this, contact us immediately and we will lock the account and investigate.',
      button: 'Contact support',
    },
    variables: [],
  },
];

// ---------------------------------------------------------------------------------------------------------------
// The <style> block: dark mode and the phone layout, for the clients that read it
// ---------------------------------------------------------------------------------------------------------------

const baseCss = `
  :root { color-scheme: light dark; supported-color-schemes: light dark; }
  body {
    margin: 0;
    padding: 0;
    width: 100% !important;
    -webkit-text-size-adjust: 100%;
    -ms-text-size-adjust: 100%;
  }
  table { border-collapse: collapse; mso-table-lspace: 0; mso-table-rspace: 0; }
  a { color: inherit; }
  .card, .hero, .pay, .step, .stage, .panel { border-collapse: separate !important; }

  @media only screen and (max-width: 620px) {
    .wrap { padding: 12px 8px !important; }
    .card { border-radius: 18px !important; }
    .px { padding-left: 22px !important; padding-right: 22px !important; }
    .h1 { font-size: 26px !important; line-height: 1.15 !important; }
    .btn a { display: block !important; text-align: center !important; }
  }

  @media (prefers-color-scheme: dark) {
    .page { background-color: #000000 !important; }
    .card { background-color: #1c1c1e !important; border-color: #38383a !important; }
    .ink { color: #f5f5f7 !important; }
    .muted { color: #98989f !important; }
    .panel { background-color: #2c2c2e !important; }
    .rule { border-color: #38383a !important; }
    .logo-light { display: none !important; max-height: 0 !important; }
    .logo-dark { display: block !important; max-height: none !important; }
    .btn a { background-color: #f5f5f7 !important; color: #0b0b0c !important; }
  }
`;

const welcomeCss = `
  @media only screen and (max-width: 620px) {
    .hero-pad { padding: 32px 24px 28px !important; }
    .hero-h { font-size: 36px !important; line-height: 1.04 !important; }
    .stage-pad { padding: 18px 14px !important; }
    .bubble { font-size: 15px !important; }
  }

  @media (prefers-color-scheme: dark) {
    .hero {
      background-color: #000000 !important;
      background-image: none !important;
      border: 1px solid #38383a !important;
    }
    .stage { background-color: #2c2c2e !important; }
    .bubble-in { background-color: #3a3a3c !important; color: #f5f5f7 !important; }
    .bubble-out { background-color: #f5f5f7 !important; color: #0b0b0c !important; }
    .pay { background-color: #1c1c1e !important; border-color: #48484a !important; }
    .step { border-color: #38383a !important; }
    .num { background-color: #f5f5f7 !important; color: #0b0b0c !important; }
  }
`;

// ---------------------------------------------------------------------------------------------------------------
// Pieces both layouts share
// ---------------------------------------------------------------------------------------------------------------

const paragraph = (text: string) =>
  `<p class="ink" style="${style('margin:0 0 16px', font(400, 16, 1.55), `color:${color.ink}`)}">${text}</p>`;

const paragraphs = (texts: string[], separator = '\n        ') => texts.map(paragraph).join(separator);

function heading(tag: 'h1' | 'h2', cls: string, size: number, tracking: string, margin: string, content: string) {
  const css = style(margin, font(700, size, 1.1), `letter-spacing:${tracking}`, `color:${color.ink}`);
  return `<${tag} class="${cls}" style="${css}">${content}</${tag}>`;
}

/** The paragraph under every email: why it arrives, and the one thing Circaso never asks for. */
function footer(isSecurityNotice: boolean): string {
  const why = isSecurityNotice
    ? 'This email is about the security of your account, so you receive it even if you turned other notifications off.'
    : 'You are getting this one email because you just created a Circaso account. ' +
      'We will not send you marketing email.';
  const small = (margin: string) => style(`margin:${margin}`, font(400, 13, 1.55), `color:${color.faint}`);

  return `<tr><td class="px rule" style="${style('padding:20px 36px 28px', `border-top:1px solid ${color.hairline}`)}">
        <p class="muted" style="${small('0')}">
          Circaso will never ask for your PIN or a code by email, and will never ask you to send money to keep
          your account safe. ${why}
        </p>
        <p class="muted" style="${small('12px 0 0')}">
          <a href="https://circaso.com" style="color:${color.faint};text-decoration:underline;">circaso.com</a>
        </p>
      </td></tr>`;
}

/** The document around a card: head, hidden preheader, and the outer table that centres the card. */
function page(m: Mail, extraCss: string, cardRadius: string, rows: string[]): string {
  const filler = '&nbsp;&zwnj;'.repeat(40); // pushes the rest of the email out of the inbox preview
  const hidden = style('display:none', 'max-height:0', 'overflow:hidden', 'opacity:0', 'mso-hide:all');
  const card = style(
    'width:100%',
    'max-width:560px',
    `background-color:${color.white}`,
    `border:1px solid ${color.hairline}`,
    `border-radius:${cardRadius}`,
  );

  return `<!doctype html>
<html lang="en" xmlns="http://www.w3.org/1999/xhtml">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="X-UA-Compatible" content="IE=edge">
<meta name="color-scheme" content="light dark">
<meta name="supported-color-schemes" content="light dark">
<title>${m.subject}</title>
<style>${baseCss}${extraCss}</style>
</head>
<body style="margin:0;padding:0;background-color:${color.panel};">
<div style="${hidden}">${m.preheader}${filler}</div>
<table ${TABLE} width="100%" class="page" style="background-color:${color.panel};">
  <tr><td align="center" class="wrap" style="padding:32px 12px;">
    <table ${TABLE} width="560" class="card" style="${card}">
      ${rows.join('\n      ')}
    </table>
  </td></tr>
</table>
</body>
</html>
`;
}

// ---------------------------------------------------------------------------------------------------------------
// The security notices: logo, text, an optional facts panel, and a "not you?" box
// ---------------------------------------------------------------------------------------------------------------

/** The logo, black on light and white on dark; clients that cannot switch show the black one. */
function logoRow(): string {
  const size = 'width="120" height="40" alt="Circaso"';
  const lightStyle = style('display:block', 'width:120px', 'height:40px', 'border:0', 'outline:none',
    font(700, 20, '40px'), `color:${color.ink}`);
  const darkStyle = style('display:none', 'width:120px', 'height:40px', 'max-height:0', 'overflow:hidden', 'border:0',
    font(700, 20, '40px'), 'color:#f5f5f7');

  return `<tr><td class="px" style="padding:28px 36px 0;">
        <a href="https://circaso.com" style="text-decoration:none;">
          <img class="logo-light" src="${ASSETS}/circaso-logo-black-480.png" ${size} style="${lightStyle}">
          <!--[if !mso]><!-->
          <img class="logo-dark" src="${ASSETS}/circaso-logo-white-480.png" ${size} style="${darkStyle}">
          <!--<![endif]-->
        </a>
      </td></tr>`;
}

function factsPanel(facts: [string, string][]): string {
  const value = style('margin-top:4px', font(600, 17, 1.35), 'letter-spacing:-.01em', `color:${color.ink}`);
  const panel = style('margin:4px 0 20px', `background-color:${color.panel}`, 'border-radius:14px');
  const row = ([label, content]: [string, string]) => `<tr><td style="padding:14px 18px;">
              <div class="muted" style="${text.label(12)}">${label}</div>
              <div class="ink" style="${value}">${content}</div>
            </td></tr>`;

  return `<table ${TABLE} width="100%" class="panel" style="${panel}">
            ${facts.map(row).join('\n            ')}
          </table>`;
}

function stepsList(steps: [string, string, string?][]): string {
  const badge = style('width:28px', 'height:28px', 'border-radius:14px', `background-color:${color.panel}`,
    `color:${color.ink}`, 'text-align:center', font(600, 14, '28px'));
  const row = ([title, body]: [string, string, string?], i: number) => `<tr>
              <td width="36" valign="top" style="padding:0 0 16px;">
                <div class="panel ink" style="${badge}">${i + 1}</div>
              </td>
              <td valign="top" style="padding:0 0 16px;">
                <div class="ink" style="${text.rowTitle(600, 16, '-.01em')}">${title}</div>
                <div class="muted" style="${text.rowText('margin-top:2px', 15)}">${body}</div>
              </td>
            </tr>`;

  return `<table ${TABLE} width="100%" style="margin:0 0 20px;">
            ${steps.map(row).join('\n            ')}
          </table>`;
}

function notYouBox(box: NonNullable<Mail['notYou']>): string {
  const button = style('display:inline-block', 'padding:12px 22px', `background-color:${color.ink}`,
    `color:${color.white}`, 'border-radius:999px', font(600, 15, 1), 'text-decoration:none');

  return `<table ${TABLE} width="100%" class="rule"
            style="margin-top:8px;border-top:1px solid ${color.hairline};">
            <tr><td style="padding-top:22px;">
              <div class="ink" style="${text.rowTitle(600, 17, '-.01em')}">${box.heading}</div>
              <p class="muted" style="${text.rowText('margin:6px 0 18px', 15, 1.55)}">${box.text}</p>
              <table ${TABLE} class="btn"><tr><td>
                <a href="mailto:${SUPPORT}" style="${button}">${box.button}</a>
              </td></tr></table>
            </td></tr>
          </table>`;
}

function renderNotice(m: Mail): string {
  const eyebrow = style('margin-bottom:10px', font(500, 13, 1.3), `color:${color.muted}`);
  const body = [
    m.intro.map(paragraph).join('\n        '),
    m.steps ? stepsList(m.steps) : '',
    m.facts ? factsPanel(m.facts) : '',
    m.outro.map(paragraph).join('\n        '),
  ];

  return page(m, '', '22px', [
    logoRow(),
    `<tr><td class="px" style="padding:36px 36px 8px;">
        <div class="muted" style="${eyebrow}">${m.eyebrow}</div>
        ${heading('h1', 'h1 ink', 30, '-.03em', 'margin:0 0 18px', m.title)}
        ${body.join('\n        ')}
      </td></tr>`,
    `<tr><td class="px" style="padding:0 36px 32px;">
        ${m.notYou ? notYouBox(m.notYou) : ''}
      </td></tr>`,
    footer(Boolean(m.notYou)),
  ]);
}

// ---------------------------------------------------------------------------------------------------------------
// The welcome: a dark hero, a chat thread with money in it, and bigger first steps
// ---------------------------------------------------------------------------------------------------------------

function hero(m: Mail): string {
  const gradient = 'linear-gradient(160deg,#0b0b0c 0%,#1c1c1e 55%,#2c2c2e 100%)';
  const box = style(`background-color:${color.ink}`, `background-image:${gradient}`, 'border-radius:20px');
  const label = style(font(600, 13, 1.3), 'letter-spacing:.08em', 'text-transform:uppercase', 'color:#98989f');
  const title = style('margin:10px 0 14px', font(700, 46, 1.02), 'letter-spacing:-.04em', `color:${color.white}`);
  const lead = style('margin:0', font(400, 17, 1.5), 'color:#c7c7cc', 'max-width:420px');
  const logo = style('display:block', 'width:132px', 'height:44px', 'border:0', font(700, 22, '44px'),
    `color:${color.white}`);
  const spacer = style('height:40px', 'line-height:40px', 'font-size:1px');

  return `<tr><td style="padding:10px 10px 0;">
        <table ${TABLE} width="100%" class="hero" bgcolor="${color.ink}" style="${box}">
          <tr><td class="hero-pad" style="padding:40px 36px 36px;">
            <img src="${ASSETS}/circaso-logo-white-480.png" width="132" height="44" alt="Circaso" style="${logo}">
            <div style="${spacer}">&nbsp;</div>
            <div style="${label}">${m.eyebrow}</div>
            <h1 class="hero-h" style="${title}">Your chats<br>just learned<br>to move money.</h1>
            <p style="${lead}">${m.intro[0]}</p>
          </td></tr>
        </table>
      </td></tr>`;
}

function bubble(side: 'in' | 'out', text: string): string {
  const colors = side === 'in'
    ? `background-color:${color.white};color:${color.ink}`
    : `background-color:${color.ink};color:${color.white}`;
  const cell = style('padding:10px 15px', 'border-radius:19px', colors, font(400, 16, 1.4));

  return `<tr><td style="padding:0 0 8px;">
                <table ${TABLE} align="${side === 'in' ? 'left' : 'right'}" style="max-width:78%;">
                  <tr><td class="bubble bubble-${side}" style="${cell}">${text}</td></tr>
                </table>
              </td></tr>`;
}

function paymentChip(amount: string): string {
  const chip = style(`background-color:${color.white}`, `border:1px solid ${color.hairline}`, 'border-radius:19px');
  const value = style('margin-top:3px', font(700, 26, 1.1), 'letter-spacing:-.03em', `color:${color.ink}`);

  return `<tr><td style="padding:0 0 8px;">
                <table ${TABLE} align="right" class="pay" style="${chip}">
                  <tr><td style="padding:13px 20px 13px 18px;">
                    <div class="muted" style="${text.label(12, 1.2)}">Sent</div>
                    <div class="ink" style="${value}">${amount}</div>
                  </td></tr>
                </table>
              </td></tr>`;
}

function chatStage(): string {
  const stage = style(`background-color:${color.panel}`, 'border-radius:22px');
  const caption = style('margin:10px 4px 0', 'text-align:center', font(400, 13, 1.4), `color:${color.faint}`);

  return `<tr><td class="px" style="padding:28px 26px 4px;">
        <table ${TABLE} width="100%" class="stage" style="${stage}">
          <tr><td class="stage-pad" style="padding:22px 20px 14px;">
            <table ${TABLE} width="100%">
              ${bubble('in', 'Rent is due Friday')}
              ${bubble('out', 'Sending it now')}
              ${paymentChip('KES 15,000')}
              ${bubble('in', 'Got it, thank you!')}
            </table>
          </td></tr>
        </table>
        <p class="muted" style="${caption}">Messages, money and your people, in the same thread.</p>
      </td></tr>`;
}

/** A step's marker: its icon when it has one (Asha), otherwise its number. */
function stepMarker(icon: string | undefined, number: number): string {
  if (icon) {
    const img = style('display:block', 'width:34px', 'height:34px', 'border-radius:17px', 'border:0');
    return `<img src="${ASSETS}/${icon}" width="34" height="34" alt="Asha" style="${img}">`;
  }
  const badge = style('width:34px', 'height:34px', 'border-radius:17px', `background-color:${color.ink}`,
    `color:${color.white}`, 'text-align:center', font(700, 16, '34px'));
  return `<div class="num" style="${badge}">${number}</div>`;
}

function welcomeSteps(steps: [string, string, string?][]): string {
  const box = style(`border:1px solid ${color.hairline}`, 'border-radius:16px');
  const row = ([title, body, icon]: [string, string, string?], i: number) => `<tr><td style="padding:0 0 10px;">
          <table ${TABLE} width="100%" class="step" style="${box}">
            <tr>
              <td width="64" valign="top" style="padding:18px 0 18px 18px;">${stepMarker(icon, i + 1)}</td>
              <td valign="top" style="padding:17px 18px 17px 0;">
                <div class="ink" style="${text.rowTitle(700, 17, '-.015em')}">${title}</div>
                <div class="muted" style="${text.rowText('margin-top:3px', 15)}">${body}</div>
              </td>
            </tr>
          </table></td></tr>`;

  return steps.map(row).join('\n        ');
}

function renderWelcome(m: Mail): string {
  const signOff = style('margin:0', font(600, 16, 1.5), `color:${color.ink}`);
  const team = style('font-weight:400', `color:${color.muted}`);

  return page(m, welcomeCss, '28px', [
    hero(m),
    chatStage(),
    `<tr><td class="px" style="padding:30px 36px 6px;">
        ${heading('h2', 'ink', 26, '-.03em', 'margin:0 0 14px', 'Start with these three')}
        <table ${TABLE} width="100%">
        ${welcomeSteps(m.steps ?? [])}
        </table>
      </td></tr>`,
    `<tr><td class="px" style="padding:18px 36px 32px;">
        ${paragraphs(m.outro, '')}
        <p class="ink" style="${signOff}">
          Glad you are here.<br><span class="muted" style="${team}">The Circaso team</span>
        </p>
      </td></tr>`,
    footer(false),
  ]);
}

// ---------------------------------------------------------------------------------------------------------------
// Build
// ---------------------------------------------------------------------------------------------------------------

const render = (m: Mail) => (m.file === 'welcome.html' ? renderWelcome(m) : renderNotice(m));

/** Throws unless the Resend variables in the HTML are exactly the ones the mail declares. */
function checkVariables(m: Mail, html: string): void {
  const used = new Set([...html.matchAll(/\{\{\{?\s*([A-Za-z0-9_]+)\s*\}?\}\}/g)].map((x) => x[1]));
  const wanted = new Set(m.variables);
  const unknown = [...used].some((v) => !wanted.has(v));
  const unused = [...wanted].some((v) => !used.has(v));
  if (unknown || unused) {
    throw new Error(`${m.file}: variables used [${[...used]}] do not match declared [${m.variables}]`);
  }
}

for (const m of mails) {
  const html = render(m);
  checkVariables(m, html);
  if (html.length > MAX_BYTES) throw new Error(`${m.file} is ${html.length} bytes; Gmail clips mail over about 102 KB`);
  writeFileSync(join(import.meta.dir, m.file), html);
  console.log(m.file.padEnd(26), String(html.length).padStart(5), 'bytes  subject:', m.subject);
}
