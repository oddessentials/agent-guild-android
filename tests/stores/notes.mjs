import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { changes, fit, LIMIT, storeNotes } from '../../tools/stores/notes.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

// The shape semantic-release gives a release, plus the Install section the release job adds.
const body = `## [1.2.0](https://github.com/oddessentials/agent-guild-android/compare/v1.1.0...v1.2.0) (2026-11-02)

### Features

* show the \`Yard\` on Google TV ([#30](https://github.com/oddessentials/agent-guild-android/issues/30)) ([abc1234](https://github.com/oddessentials/agent-guild-android/commit/abc1234))
* **board:** keep the [focused card](https://example.com) in view ([def5678](https://github.com/oddessentials/agent-guild-android/commit/def5678))

### Bug Fixes

* reconnect after the device sleeps ([#31](https://github.com/oddessentials/agent-guild-android/issues/31)) ([0123abc](https://github.com/oddessentials/agent-guild-android/commit/0123abc))


### Install

Download \`agent-guild-1.2.0.apk\` below and open it on the Android device. It installs over an earlier release.

SHA-256 of \`agent-guild-1.2.0.apk\`: \`${'a'.repeat(64)}\`
`;

test('the changes are the changelog bullets without links, hashes, scopes or markup', () => {
  assert.deepEqual(changes(body), [
    'Show the Yard on Google TV',
    'Keep the focused card in view',
    'Reconnect after the device sleeps',
  ]);
});

test('the store text is one change per line', () => {
  assert.equal(storeNotes({ body }), '• Show the Yard on Google TV\n• Keep the focused card in view\n• Reconnect after the device sleeps');
});

test('a changelog with no changes gives no text', () => {
  assert.equal(storeNotes({ body: '## [1.0.1](x) (2026-11-02)\n\n### Install\n\n* not a change\n' }), '');
});

test('too many changes drop whole lines from the end and say there are more', () => {
  const items = Array.from({ length: 40 }, (_, i) => `Change number ${i + 1} with some words`);
  const text = fit(items);
  assert.ok(text.length <= LIMIT);
  assert.match(text, /^• Change number 1 with some words\n/);
  assert.match(text, /\n…and more\.$/);
  for (const line of text.split('\n').slice(0, -1)) assert.match(line, /^• Change number \d+ with some words$/);
});

test('one change longer than the limit is cut to fit', () => {
  const text = fit(['x'.repeat(LIMIT * 2)]);
  assert.equal(text.length, LIMIT);
  assert.match(text, /…$/);
});

test('STORE_NOTES replaces the changelog, and may not pass the limit', () => {
  assert.equal(storeNotes({ body, override: '  Faster sign-in.  ' }), 'Faster sign-in.');
  assert.equal(storeNotes({ body, override: '   ' }), storeNotes({ body }));
  assert.throws(() => storeNotes({ body, override: 'x'.repeat(LIMIT + 1) }), /at most 500/);
});

test('the CLI writes the text and puts it in the run summary', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'notes-'));
  const input = path.join(dir, 'body.md');
  const output = path.join(dir, 'notes.txt');
  const summary = path.join(dir, 'summary');
  fs.writeFileSync(input, body);
  const { STORE_NOTES, ...env } = process.env;
  execFileSync(process.execPath, [path.join(root, 'tools/stores/notes.mjs'), input, output], {
    env: { ...env, GITHUB_STEP_SUMMARY: summary }, stdio: 'pipe',
  });
  assert.equal(fs.readFileSync(output, 'utf8'), storeNotes({ body }));
  assert.match(fs.readFileSync(summary, 'utf8'), /> • Show the Yard on Google TV/);
  fs.rmSync(dir, { recursive: true, force: true });
});

test('every store job hands the notes to its publish step', () => {
  const workflow = fs.readFileSync(path.join(root, '.github/workflows/stores.yml'), 'utf8');
  assert.match(workflow, /node tools\/stores\/notes\.mjs /);
  for (const job of ['meta', 'amazon', 'samsung', 'google-play']) {
    const block = workflow.split(new RegExp(`^  ${job}:$`, 'm'))[1].split(/^  [a-z-]+:$/m)[0];
    assert.match(block, /name: store-notes/);
    assert.match(block, new RegExp(`STORE_NOTES_FILE: \\$\\{\\{ runner\\.temp \\}\\}/notes/notes\\.txt\\n        run: node tools/stores/${job}\\.mjs publish`));
  }
});
