// Builds the three Resend templates into this folder: `bun run build.ts`. Each .html file is self-contained, so it can be
// copied whole and pasted into Resend. The layout lives here once so the three stay identical.
//
// Email HTML is not web HTML: layout is tables, styles are inline (the <style> block only adds dark mode and the phone
// layout, for the clients that read it), the only image is the logo (hosted on assets.circaso.com), and no glass, blur or variable fonts, which mail
// clients ignore. The design carries over circaso.com's tokens: ink on white, grouped grey panels, hairline borders,
// the system font with tight tracking on headings, and a black pill button.
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';

const ASSETS = 'https://assets.circaso.com/brand'; // the circaso-static R2 bucket (public, assets.circaso.com)
const SUPPORT = 'support@circaso.com'; // TODO(decision: the address people reply to for security help)

const font = "-apple-system, BlinkMacSystemFont, 'SF Pro Text', 'Helvetica Neue', Helvetica, Arial, sans-serif";

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
  /** Numbered first steps (title, one line), shown after the intro. */
  steps?: [string, string, string?][];
  /** The "not you?" box. */
  notYou?: { heading: string; text: string; button: string };
  variables: string[];
}

const mails: Mail[] = [
  {
    file: 'welcome.html',
    subject: 'Welcome to Circaso',
    preheader: 'Your account is ready. Three things to try first.',
    eyebrow: 'Welcome',
    title: 'Welcome to Circaso',
    intro: ['Your account is ready. Circaso is where you talk to the people you know and send, receive and save money with them, without leaving the conversation.'],
    steps: [
      ['Add your contacts', 'Circaso shows you who you already know here, and lets you invite the rest.'],
      ['Claim your handle', 'A short name people can use to find you, so you never have to share your number.'],
      ['Ask Asha', 'Asha is the assistant in your chat. Ask a question, or ask it to help with something.', 'asha/asha-icon-silver-96.png'],
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
    intro: ['Someone signed in to your Circaso account on a phone we have not seen before, and your account now works on that phone.'],
    facts: [['Phone', '{{{DEVICE_MODEL}}}']],
    outro: ['If this was you, there is nothing to do. Circaso signs you out of your old phone, so it will ask you to sign in again.'],
    notYou: {
      heading: 'Not you?',
      text: 'Open Circaso on a phone you trust, review your signed-in devices under Account and security, and tell us straight away so we can protect your account.',
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
      'Our team checks every request before anything changes. Your account, your money and your messages stay as they are until then, and you will get another email before any change takes effect.',
    ],
    outro: ['If you made this request, you do not need to do anything.'],
    notYou: {
      heading: 'Did not ask for this?',
      text: 'Tell us now. We will stop the request and help you secure your account. Do not share codes or your PIN with anyone, including people who say they are from Circaso.',
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
    intro: ['We finished the recovery you asked for. Your account now signs in the new way, and the old sign-in no longer works.', 'Your money, messages and contacts are exactly as you left them.'],
    outro: ['You can sign in to Circaso now.'],
    notYou: {
      heading: 'Not you?',
      text: 'If you did not ask for this, contact us immediately and we will lock the account and investigate.',
      button: 'Contact support',
    },
    variables: [],
  },
];

const css = `
  :root { color-scheme: light dark; supported-color-schemes: light dark; }
  body { margin: 0; padding: 0; width: 100% !important; -webkit-text-size-adjust: 100%; -ms-text-size-adjust: 100%; }
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
    .hero { background-color: #000000 !important; background-image: none !important; border: 1px solid #38383a !important; }
    .stage { background-color: #2c2c2e !important; }
    .bubble-in { background-color: #3a3a3c !important; color: #f5f5f7 !important; }
    .bubble-out { background-color: #f5f5f7 !important; color: #0b0b0c !important; }
    .pay { background-color: #1c1c1e !important; border-color: #48484a !important; }
    .step { border-color: #38383a !important; }
    .num { background-color: #f5f5f7 !important; color: #0b0b0c !important; }
  }
`;

const p = (text: string, cls = 'ink') =>
  `<p class="${cls}" style="margin:0 0 16px;font:400 16px/1.55 ${font};color:${cls === 'ink' ? '#0b0b0c' : '#636366'};">${text}</p>`;

function render(m: Mail): string {
  if (m.file === 'welcome.html') return renderWelcome(m);
  const facts = m.facts
    ? `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" class="panel" style="margin:4px 0 20px;background-color:#f2f2f7;border-radius:14px;">
${m.facts
  .map(
    ([label, value]) => `            <tr><td style="padding:14px 18px;">
              <div class="muted" style="font:500 12px/1.3 ${font};letter-spacing:.04em;text-transform:uppercase;color:#636366;">${label}</div>
              <div class="ink" style="margin-top:4px;font:600 17px/1.35 ${font};letter-spacing:-.01em;color:#0b0b0c;">${value}</div>
            </td></tr>`,
  )
  .join('\n')}
          </table>`
    : '';
  const steps = m.steps
    ? `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="margin:0 0 20px;">
${m.steps
  .map(
    ([title, text], i) => `            <tr><td width="36" valign="top" style="padding:0 0 16px;"><div class="panel ink" style="width:28px;height:28px;border-radius:14px;background-color:#f2f2f7;color:#0b0b0c;text-align:center;font:600 14px/28px ${font};">${i + 1}</div></td>
              <td valign="top" style="padding:0 0 16px;"><div class="ink" style="font:600 16px/1.35 ${font};letter-spacing:-.01em;color:#0b0b0c;">${title}</div>
              <div class="muted" style="margin-top:2px;font:400 15px/1.5 ${font};color:#636366;">${text}</div></td></tr>`,
  )
  .join('\n')}
          </table>`
    : '';
  const notYou = m.notYou
    ? `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" class="rule" style="margin-top:8px;border-top:1px solid #e3e3e8;">
            <tr><td style="padding-top:22px;">
              <div class="ink" style="font:600 17px/1.3 ${font};letter-spacing:-.01em;color:#0b0b0c;">${m.notYou.heading}</div>
              <p class="muted" style="margin:6px 0 18px;font:400 15px/1.55 ${font};color:#636366;">${m.notYou.text}</p>
              <table role="presentation" cellpadding="0" cellspacing="0" border="0" class="btn"><tr><td>
                <a href="mailto:${SUPPORT}" style="display:inline-block;padding:12px 22px;background-color:#0b0b0c;color:#ffffff;border-radius:999px;font:600 15px/1 ${font};text-decoration:none;">${m.notYou.button}</a>
              </td></tr></table>
            </td></tr>
          </table>`
    : '';

  return `<!doctype html>
<html lang="en" xmlns="http://www.w3.org/1999/xhtml">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="X-UA-Compatible" content="IE=edge">
<meta name="color-scheme" content="light dark">
<meta name="supported-color-schemes" content="light dark">
<title>${m.subject}</title>
<style>${css}</style>
</head>
<body style="margin:0;padding:0;background-color:#f2f2f7;">
<div style="display:none;max-height:0;overflow:hidden;opacity:0;mso-hide:all;">${m.preheader}${'&nbsp;&zwnj;'.repeat(40)}</div>
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" class="page" style="background-color:#f2f2f7;">
  <tr><td align="center" class="wrap" style="padding:32px 12px;">
    <table role="presentation" width="560" cellpadding="0" cellspacing="0" border="0" class="card" style="width:100%;max-width:560px;background-color:#ffffff;border:1px solid #e3e3e8;border-radius:22px;">
      <tr><td class="px" style="padding:28px 36px 0;">
        <a href="https://circaso.com" style="text-decoration:none;">
          <img class="logo-light" src="${ASSETS}/circaso-logo-black-480.png" width="120" height="40" alt="Circaso" style="display:block;width:120px;height:40px;border:0;outline:none;font:700 20px/40px ${font};color:#0b0b0c;">
          <!--[if !mso]><!--><img class="logo-dark" src="${ASSETS}/circaso-logo-white-480.png" width="120" height="40" alt="Circaso" style="display:none;width:120px;height:40px;max-height:0;overflow:hidden;border:0;font:700 20px/40px ${font};color:#f5f5f7;"><!--<![endif]-->
        </a>
      </td></tr>
      <tr><td class="px" style="padding:36px 36px 8px;">
        <div class="muted" style="margin-bottom:10px;font:500 13px/1.3 ${font};color:#636366;">${m.eyebrow}</div>
        <h1 class="h1 ink" style="margin:0 0 18px;font:700 30px/1.1 ${font};letter-spacing:-.03em;color:#0b0b0c;">${m.title}</h1>
        ${m.intro.map((t) => p(t)).join('\n        ')}
        ${steps}
        ${facts}
        ${m.outro.map((t) => p(t)).join('\n        ')}
      </td></tr>
      <tr><td class="px" style="padding:0 36px 32px;">
        ${notYou}
      </td></tr>
      <tr><td class="px rule" style="padding:20px 36px 28px;border-top:1px solid #e3e3e8;">
        <p class="muted" style="margin:0;font:400 13px/1.55 ${font};color:#7c7c80;">Circaso will never ask for your PIN or a code by email, and will never ask you to send money to keep your account safe. ${m.notYou ? 'This email is about the security of your account, so you receive it even if you turned other notifications off.' : 'You are getting this one email because you just created a Circaso account. We will not send you marketing email.'}</p>
        <p class="muted" style="margin:12px 0 0;font:400 13px/1.55 ${font};color:#7c7c80;"><a href="https://circaso.com" style="color:#7c7c80;text-decoration:underline;">circaso.com</a></p>
      </td></tr>
    </table>
  </td></tr>
</table>
</body>
</html>
`;
}


/** The welcome has its own layout: a dark hero, a chat thread with money in it, and bigger first steps. */
function renderWelcome(m: Mail): string {
  const bubble = (side: 'in' | 'out', text: string) =>
    `<tr><td style="padding:0 0 8px;"><table role="presentation" align="${side === 'in' ? 'left' : 'right'}" cellpadding="0" cellspacing="0" border="0" style="max-width:78%;"><tr><td class="bubble bubble-${side}" style="padding:10px 15px;border-radius:19px;${side === 'in' ? 'background-color:#ffffff;color:#0b0b0c;' : 'background-color:#0b0b0c;color:#ffffff;'}font:400 16px/1.4 ${font};">${text}</td></tr></table></td></tr>`;
  const steps = (m.steps ?? [])
    .map(
      ([title, text, icon], i) => `<tr><td style="padding:0 0 10px;">
          <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" class="step" style="border:1px solid #e3e3e8;border-radius:16px;"><tr>
            <td width="64" valign="top" style="padding:18px 0 18px 18px;">${icon ? `<img src="${ASSETS}/${icon}" width="34" height="34" alt="Asha" style="display:block;width:34px;height:34px;border-radius:17px;border:0;">` : `<div class="num" style="width:34px;height:34px;border-radius:17px;background-color:#0b0b0c;color:#ffffff;text-align:center;font:700 16px/34px ${font};">${i + 1}</div>`}</td>
            <td valign="top" style="padding:17px 18px 17px 0;"><div class="ink" style="font:700 17px/1.3 ${font};letter-spacing:-.015em;color:#0b0b0c;">${title}</div>
              <div class="muted" style="margin-top:3px;font:400 15px/1.5 ${font};color:#636366;">${text}</div></td>
          </tr></table></td></tr>`,
    )
    .join('\n');

  return `<!doctype html>
<html lang="en" xmlns="http://www.w3.org/1999/xhtml">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="X-UA-Compatible" content="IE=edge">
<meta name="color-scheme" content="light dark">
<meta name="supported-color-schemes" content="light dark">
<title>${m.subject}</title>
<style>${css}${welcomeCss}</style>
</head>
<body style="margin:0;padding:0;background-color:#f2f2f7;">
<div style="display:none;max-height:0;overflow:hidden;opacity:0;mso-hide:all;">${m.preheader}${'&nbsp;&zwnj;'.repeat(40)}</div>
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" class="page" style="background-color:#f2f2f7;">
  <tr><td align="center" class="wrap" style="padding:32px 12px;">
    <table role="presentation" width="560" cellpadding="0" cellspacing="0" border="0" class="card" style="width:100%;max-width:560px;background-color:#ffffff;border:1px solid #e3e3e8;border-radius:28px;">
      <tr><td style="padding:10px 10px 0;">
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" class="hero" bgcolor="#0b0b0c" style="background-color:#0b0b0c;background-image:linear-gradient(160deg,#0b0b0c 0%,#1c1c1e 55%,#2c2c2e 100%);border-radius:20px;">
          <tr><td class="hero-pad" style="padding:40px 36px 36px;">
            <img src="${ASSETS}/circaso-logo-white-480.png" width="132" height="44" alt="Circaso" style="display:block;width:132px;height:44px;border:0;font:700 22px/44px ${font};color:#ffffff;">
            <div style="height:40px;line-height:40px;font-size:1px;">&nbsp;</div>
            <div style="font:600 13px/1.3 ${font};letter-spacing:.08em;text-transform:uppercase;color:#98989f;">Welcome</div>
            <h1 class="hero-h" style="margin:10px 0 14px;font:700 46px/1.02 ${font};letter-spacing:-.04em;color:#ffffff;">Your chats<br>just learned<br>to move money.</h1>
            <p style="margin:0;font:400 17px/1.5 ${font};color:#c7c7cc;max-width:420px;">${m.intro[0]}</p>
          </td></tr>
        </table>
      </td></tr>
      <tr><td class="px" style="padding:28px 26px 4px;">
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" class="stage" style="background-color:#f2f2f7;border-radius:22px;">
          <tr><td class="stage-pad" style="padding:22px 20px 14px;">
            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0">
              ${bubble('in', 'Rent is due Friday')}
              ${bubble('out', 'Sending it now')}
              <tr><td style="padding:0 0 8px;">
                <table role="presentation" align="right" cellpadding="0" cellspacing="0" border="0" class="pay" style="background-color:#ffffff;border:1px solid #e3e3e8;border-radius:19px;"><tr><td style="padding:13px 20px 13px 18px;">
                  <div class="muted" style="font:500 12px/1.2 ${font};letter-spacing:.04em;text-transform:uppercase;color:#636366;">Sent</div>
                  <div class="ink" style="margin-top:3px;font:700 26px/1.1 ${font};letter-spacing:-.03em;color:#0b0b0c;">KES 15,000</div>
                </td></tr></table>
              </td></tr>
              ${bubble('in', 'Got it, thank you!')}
            </table>
          </td></tr>
        </table>
        <p class="muted" style="margin:10px 4px 0;text-align:center;font:400 13px/1.4 ${font};color:#7c7c80;">Messages, money and your people, in the same thread.</p>
      </td></tr>
      <tr><td class="px" style="padding:30px 36px 6px;">
        <h2 class="ink" style="margin:0 0 14px;font:700 26px/1.1 ${font};letter-spacing:-.03em;color:#0b0b0c;">Start with these three</h2>
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0">${steps}</table>
      </td></tr>
      <tr><td class="px" style="padding:18px 36px 32px;">
        ${m.outro.map((t) => p(t)).join('')}
        <p class="ink" style="margin:0;font:600 16px/1.5 ${font};color:#0b0b0c;">Glad you are here.<br><span class="muted" style="font-weight:400;color:#636366;">The Circaso team</span></p>
      </td></tr>
      <tr><td class="px rule" style="padding:20px 36px 28px;border-top:1px solid #e3e3e8;">
        <p class="muted" style="margin:0;font:400 13px/1.55 ${font};color:#7c7c80;">Circaso will never ask for your PIN or a code by email, and will never ask you to send money to keep your account safe. You are getting this one email because you just created a Circaso account. We will not send you marketing email.</p>
        <p class="muted" style="margin:12px 0 0;font:400 13px/1.55 ${font};color:#7c7c80;"><a href="https://circaso.com" style="color:#7c7c80;text-decoration:underline;">circaso.com</a></p>
      </td></tr>
    </table>
  </td></tr>
</table>
</body>
</html>
`;
}

for (const m of mails) {
  const html = render(m);
  const used = new Set([...html.matchAll(/\{\{\{?\s*([A-Za-z0-9_]+)\s*\}?\}\}/g)].map((x) => x[1]));
  const wanted = new Set(m.variables);
  if ([...used].some((v) => !wanted.has(v)) || [...wanted].some((v) => !used.has(v))) {
    throw new Error(`${m.file}: variables used [${[...used]}] do not match declared [${m.variables}]`);
  }
  if (html.length > 90_000) throw new Error(`${m.file} is ${html.length} bytes; Gmail clips mail over about 102 KB`);
  writeFileSync(join(import.meta.dir, m.file), html);
  console.log(m.file.padEnd(26), String(html.length).padStart(5), 'bytes  subject:', m.subject);
}
