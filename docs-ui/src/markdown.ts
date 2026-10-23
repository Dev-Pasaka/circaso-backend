/**
 * A small, safe Markdown renderer for the descriptions in the spec: headings, paragraphs, lists, tables, fenced code,
 * `code`, **bold**, *italic* and links. Text is escaped first and only then marked up, and a link is kept only if it is
 * http(s), mailto, a path or an in-page anchor, so nothing in the spec can inject markup or a script.
 */

export function escapeHtml(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

export function slugify(text: string): string {
  return text.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
}

const SAFE_URL = /^(https?:\/\/|mailto:|\/|#)/i;

function emphasis(escaped: string): string {
  return escaped
    .replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (_m, label: string, url: string) =>
      SAFE_URL.test(url) ? `<a href="${url}" rel="noopener noreferrer"${/^https?:/i.test(url) ? ' target="_blank"' : ''}>${label}</a>` : label,
    )
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/(^|[^*\w])\*([^*\s][^*]*?)\*(?![*\w])/g, '$1<em>$2</em>');
}

/** Inline markup: code spans are cut out first so nothing inside them is touched. */
export function inline(text: string): string {
  return text
    .split(/(`[^`]+`)/g)
    .map((part) => (part.length > 2 && part.startsWith('`') && part.endsWith('`') ? `<code>${escapeHtml(part.slice(1, -1))}</code>` : emphasis(escapeHtml(part))))
    .join('');
}

/** Splits a table row on `|`, except inside a code span (`a|b` is one cell). */
export function splitRow(line: string): string[] {
  const cells: string[] = [];
  let current = '';
  let inCode = false;
  for (const ch of line.trim().replace(/^\|/, '').replace(/\|$/, '')) {
    if (ch === '`') inCode = !inCode;
    if (ch === '|' && !inCode) {
      cells.push(current.trim());
      current = '';
    } else {
      current += ch;
    }
  }
  cells.push(current.trim());
  return cells;
}

const isTableSeparator = (line: string) => /^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$/.test(line);

export interface Heading {
  level: number;
  text: string;
  id: string;
}

export interface Rendered {
  html: string;
  headings: Heading[];
}

export function render(markdown: string): Rendered {
  const lines = markdown.replace(/\r\n/g, '\n').split('\n');
  const out: string[] = [];
  const headings: Heading[] = [];
  let i = 0;

  const startsBlock = (line: string) => /^(#{1,4}\s|```|\s*[-*]\s|\s*\d+\.\s|\|)/.test(line);

  while (i < lines.length) {
    const line = lines[i];
    if (line.trim() === '') {
      i++;
      continue;
    }

    const fence = line.match(/^```(\w*)/);
    if (fence) {
      const code: string[] = [];
      i++;
      while (i < lines.length && !lines[i].startsWith('```')) code.push(lines[i++]);
      i++;
      out.push(`<pre class="code" data-lang="${escapeHtml(fence[1])}"><code>${fence[1] === 'json' ? highlightJson(code.join('\n')) : escapeHtml(code.join('\n'))}</code></pre>`);
      continue;
    }

    const heading = line.match(/^(#{1,4})\s+(.*)$/);
    if (heading) {
      const level = heading[1].length;
      const id = slugify(heading[2]);
      headings.push({ level, text: heading[2], id });
      out.push(`<h${level} id="${id}">${inline(heading[2])}</h${level}>`);
      i++;
      continue;
    }

    if (line.trim().startsWith('|') && i + 1 < lines.length && isTableSeparator(lines[i + 1])) {
      const head = splitRow(line);
      i += 2;
      const rows: string[][] = [];
      while (i < lines.length && lines[i].trim().startsWith('|')) rows.push(splitRow(lines[i++]));
      out.push(
        `<div class="table-wrap"><table><thead><tr>${head.map((c) => `<th>${inline(c)}</th>`).join('')}</tr></thead><tbody>` +
          rows.map((r) => `<tr>${r.map((c) => `<td>${inline(c)}</td>`).join('')}</tr>`).join('') +
          '</tbody></table></div>',
      );
      continue;
    }

    const list = line.match(/^(\s*)([-*]|\d+\.)\s+(.*)$/);
    if (list) {
      const ordered = /\d/.test(list[2]);
      const items: string[] = [];
      while (i < lines.length) {
        const item = lines[i].match(/^(\s*)([-*]|\d+\.)\s+(.*)$/);
        if (item && /\d/.test(item[2]) === ordered) {
          items.push(item[3]);
          i++;
        } else if (item === null && lines[i].trim() !== '' && /^\s+/.test(lines[i]) && items.length) {
          items[items.length - 1] += ' ' + lines[i].trim();
          i++;
        } else {
          break;
        }
      }
      const tag = ordered ? 'ol' : 'ul';
      out.push(`<${tag}>${items.map((t) => `<li>${inline(t)}</li>`).join('')}</${tag}>`);
      continue;
    }

    const paragraph: string[] = [];
    while (i < lines.length && lines[i].trim() !== '' && !(paragraph.length && startsBlock(lines[i]))) paragraph.push(lines[i++].trim());
    out.push(`<p>${inline(paragraph.join(' '))}</p>`);
  }
  return { html: out.join('\n'), headings };
}

/** Colors JSON for display. Works on the raw text and escapes every piece, so it cannot inject markup either. */
export function highlightJson(text: string): string {
  const token = /("(?:\\.|[^"\\])*")(\s*:)?|\b(true|false|null)\b|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g;
  let out = '';
  let last = 0;
  for (const m of text.matchAll(token)) {
    out += escapeHtml(text.slice(last, m.index));
    if (m[1] !== undefined) out += m[2] ? `<span class="j-key">${escapeHtml(m[1])}</span>${escapeHtml(m[2])}` : `<span class="j-str">${escapeHtml(m[1])}</span>`;
    else if (m[3] !== undefined) out += `<span class="j-lit">${m[3]}</span>`;
    else out += `<span class="j-num">${m[0]}</span>`;
    last = (m.index ?? 0) + m[0].length;
  }
  return out + escapeHtml(text.slice(last));
}
