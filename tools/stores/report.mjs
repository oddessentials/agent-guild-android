import fs from 'node:fs';
import { pathToFileURL } from 'node:url';
import { versionCodeOf } from '../release/version.mjs';

export class StoreError extends Error {}

export function configured(env, names) {
  const present = names.filter((name) => env[name]);
  if (present.length === 0) return false;
  if (present.length < names.length) {
    const missing = names.filter((name) => !env[name]);
    throw new StoreError(`Partly configured: ${missing.join(', ')} ${missing.length > 1 ? 'are' : 'is'} missing.`);
  }
  return true;
}

export function redact(text, secrets) {
  let out = String(text);
  for (const secret of secrets) if (secret) out = out.split(secret).join('***');
  return out;
}

export async function call(fetch, method, url, { headers = {}, body, secrets = [] } = {}) {
  const response = await fetch(url, { method, headers, body });
  const text = await response.text();
  if (!response.ok) {
    throw new StoreError(redact(`${method} ${new URL(url).pathname} answered ${response.status}: ${text.slice(0, 500)}`, secrets));
  }
  let json = null;
  if (text.trim()) {
    try {
      json = JSON.parse(text);
    } catch {
      json = null;
    }
  }
  return { status: response.status, headers: response.headers, text, json };
}

function write(store, result) {
  const line = `${store}: ${result.status}. ${result.message}`;
  if (result.status === 'manual action required') console.log(`::warning title=${store}::${result.message}`);
  console.log(line);
  if (process.env.GITHUB_STEP_SUMMARY) fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, `- **${store}**: ${result.status}. ${result.message}\n`);
}

export async function main(meta, store) {
  if (!process.argv[1] || pathToFileURL(process.argv[1]).href !== meta.url) return;
  const [command, apk, version] = process.argv.slice(2);
  try {
    const isConfigured = configured(process.env, store.names);
    if (command === 'configured') {
      if (process.env.GITHUB_OUTPUT) fs.appendFileSync(process.env.GITHUB_OUTPUT, `configured=${isConfigured}\n`);
      if (!isConfigured) write(store.name, { status: 'skipped', message: `Not configured: set ${store.names.join(', ')}.` });
      return;
    }
    if (command !== 'publish' || !apk || !version) throw new StoreError(`Usage: node ${process.argv[1]} configured | publish <apk> <version>`);
    if (!isConfigured) throw new StoreError(`Not configured: set ${store.names.join(', ')}.`);
    versionCodeOf(version);
    const notes = process.env.STORE_NOTES_FILE ? fs.readFileSync(process.env.STORE_NOTES_FILE, 'utf8').trim() : '';
    write(store.name, await store.publish({ env: process.env, apk, version, notes, fetch: globalThis.fetch }));
  } catch (err) {
    write(store.name, { status: 'failed', message: redact(err.message, store.names.map((n) => process.env[n])) });
    process.exitCode = 1;
  }
}
