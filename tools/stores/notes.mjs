// What's new in a release, as one plain text every store takes:
//   node tools/stores/notes.mjs <release-body.md> <notes.txt>
// The text is STORE_NOTES when set, else the changes listed in the release's changelog.
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';

// Google Play's limit, the smallest of the stores'.
export const LIMIT = 500;
const MORE = '…and more.';

// The bullet points of the changelog's sections, without links, commit hashes or scopes. The
// Install section is instructions, not changes.
export function changes(body) {
  const items = [];
  let section = '';
  for (const line of String(body).split(/\r?\n/)) {
    const heading = /^#{1,6}\s+(.*)$/.exec(line);
    if (heading) {
      section = heading[1].trim();
      continue;
    }
    const bullet = /^[*-]\s+(.*)$/.exec(line);
    if (!bullet || !section || section === 'Install') continue;
    const text = bullet[1]
      .replace(/\s*\(\[[^\]]*\]\([^)]*\)\)/g, '')
      .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
      .replace(/^\*\*[^*]+:\*\*\s*/, '')
      .replace(/[*`]/g, '')
      .trim();
    if (text) items.push(text[0].toUpperCase() + text.slice(1));
  }
  return items;
}

// One change per line, dropping whole lines from the end to fit the limit.
export function fit(items, limit = LIMIT) {
  const lines = items.map((item) => `• ${item}`);
  let text = lines.join('\n');
  if (text.length <= limit) return text;
  while (lines.length > 1) {
    lines.pop();
    text = `${lines.join('\n')}\n${MORE}`;
    if (text.length <= limit) return text;
  }
  return `${lines[0].slice(0, limit - 1)}…`;
}

export function storeNotes({ body, override }) {
  const text = String(override ?? '').trim();
  if (text) {
    if (text.length > LIMIT) throw new Error(`STORE_NOTES is ${text.length} characters; the stores take at most ${LIMIT}.`);
    return text;
  }
  return fit(changes(body));
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  const [input, output] = process.argv.slice(2);
  try {
    if (!input || !output) throw new Error(`Usage: node ${process.argv[1]} <release-body.md> <notes.txt>`);
    const notes = storeNotes({ body: fs.readFileSync(input, 'utf8'), override: process.env.STORE_NOTES });
    fs.writeFileSync(output, notes);
    console.log(notes || '(no notes)');
    if (process.env.GITHUB_STEP_SUMMARY) {
      fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, `\nWhat's new for the stores:\n\n${notes ? notes.split('\n').map((l) => `> ${l}`).join('\n') : '(none)'}\n`);
    }
  } catch (err) {
    console.log(`::error::${err.message}`);
    process.exitCode = 1;
  }
}
