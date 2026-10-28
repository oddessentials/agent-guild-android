import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { configured, redact } from '../../tools/stores/report.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const names = ['A', 'B'];

test('nothing set means not configured', () => {
  assert.equal(configured({}, names), false);
  assert.equal(configured({ A: '', B: '' }, names), false);
});

test('everything set means configured', () => {
  assert.equal(configured({ A: '1', B: '2' }, names), true);
});

test('partly set is an error naming what is missing', () => {
  assert.throws(() => configured({ A: '1' }, names), /Partly configured: B is missing/);
});

test('redact hides every secret', () => {
  assert.equal(redact('token abc and abc', ['abc', '', undefined]), 'token *** and ***');
});

function cli(script, args, env) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'stores-'));
  const files = { GITHUB_OUTPUT: path.join(dir, 'output'), GITHUB_STEP_SUMMARY: path.join(dir, 'summary') };
  const clean = Object.fromEntries(Object.entries(process.env).filter(([k]) => !/^(META|AMAZON|SAMSUNG)_/.test(k)));
  let code = 0;
  let stdout;
  try {
    stdout = execFileSync(process.execPath, [path.join(root, 'tools/stores', script), ...args], {
      env: { ...clean, ...files, ...env }, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'],
    });
  } catch (err) {
    code = err.status;
    stdout = err.stdout;
  }
  const read = (f) => (fs.existsSync(f) ? fs.readFileSync(f, 'utf8') : '');
  const result = { code, stdout, output: read(files.GITHUB_OUTPUT), summary: read(files.GITHUB_STEP_SUMMARY) };
  fs.rmSync(dir, { recursive: true, force: true });
  return result;
}

for (const script of ['meta.mjs', 'amazon.mjs', 'samsung.mjs']) {
  test(`${script} without configuration reports a skip and succeeds`, () => {
    const r = cli(script, ['configured'], {});
    assert.equal(r.code, 0);
    assert.match(r.output, /^configured=false$/m);
    assert.match(r.summary, /skipped\. Not configured/);
  });

  test(`${script} publish without configuration fails`, () => {
    const r = cli(script, ['publish', 'x.apk', '1.0.0'], {});
    assert.equal(r.code, 1);
    assert.match(r.summary, /failed\. Not configured/);
  });
}

test('partial configuration fails the configured step and hides the secret', () => {
  const r = cli('amazon.mjs', ['configured'], { AMAZON_CLIENT_SECRET: 'hunter2' });
  assert.equal(r.code, 1);
  assert.match(r.summary, /failed\. Partly configured/);
  assert.doesNotMatch(r.stdout + r.summary, /hunter2/);
});

test('full configuration is reported as configured', () => {
  const r = cli('meta.mjs', ['configured'], { META_APP_ID: '1', META_APP_SECRET: 's' });
  assert.equal(r.code, 0);
  assert.match(r.output, /^configured=true$/m);
  assert.equal(r.summary, '');
});

test('each store job depends only on the checked release, so one store failing leaves the others running', () => {
  const workflow = fs.readFileSync(path.join(root, '.github/workflows/stores.yml'), 'utf8');
  for (const job of ['meta', 'amazon', 'samsung']) {
    const block = workflow.split(new RegExp(`^  ${job}:$`, 'm'))[1].split(/^  [a-z]+:$/m)[0];
    assert.match(block, /^    needs: fetch$/m);
    assert.match(block, new RegExp(`^    if: inputs\\.${job}$`, 'm'));
    assert.match(block, new RegExp(`^    environment: store-${job}$`, 'm'));
  }
  assert.match(workflow, /^concurrency:\n  group: stores\n  cancel-in-progress: false$/m);
});
