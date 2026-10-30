import crypto from 'node:crypto';
import fs from 'node:fs';
import { PACKAGE } from '../release/apk.mjs';
import { versionCodeOf } from '../release/version.mjs';
import { call, main, StoreError } from './report.mjs';

export const NAMES = ['GOOGLE_PLAY_SERVICE_ACCOUNT_JSON'];
const TOKEN_URL = 'https://oauth2.googleapis.com/token';
const SCOPE = 'https://www.googleapis.com/auth/androidpublisher';
const API = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PACKAGE}`;
const UPLOAD = `https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/${PACKAGE}`;
const TRACKS = ['internal', 'alpha', 'beta', 'production'];
const LANGUAGE = 'en-US';

const base64url = (data) => Buffer.from(data).toString('base64url');

export function serviceAccount(json) {
  let account;
  try {
    account = JSON.parse(json);
  } catch {
    throw new StoreError('GOOGLE_PLAY_SERVICE_ACCOUNT_JSON is not JSON.');
  }
  if (account?.type !== 'service_account' || !account.client_email || !account.private_key) {
    throw new StoreError('GOOGLE_PLAY_SERVICE_ACCOUNT_JSON must be a service account key, with client_email and private_key.');
  }
  return { email: account.client_email, privateKey: account.private_key.replace(/\\n/g, '\n') };
}

export function jwt({ email, privateKey, now }) {
  const iat = Math.floor(now / 1000);
  const head = base64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  const claims = base64url(JSON.stringify({ iss: email, scope: SCOPE, aud: TOKEN_URL, iat, exp: iat + 3600 }));
  const signature = crypto.sign('sha256', Buffer.from(`${head}.${claims}`), privateKey);
  return `${head}.${claims}.${base64url(signature)}`;
}

export async function publish({ env, apk: aab, version, notes = '', fetch, read = fs.readFileSync, now = Date.now() }) {
  const code = versionCodeOf(version);
  const track = env.GOOGLE_PLAY_TRACK || 'production';
  if (!TRACKS.includes(track)) throw new StoreError(`GOOGLE_PLAY_TRACK must be one of ${TRACKS.join(', ')}, not "${track}".`);
  const assertion = jwt({ ...serviceAccount(env.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON), now });
  const auth = await call(fetch, 'POST', TOKEN_URL, {
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion }).toString(),
    secrets: [assertion],
  });
  const bearer = auth.json?.access_token;
  if (!bearer) throw new StoreError('Google gave no access token.');
  const secrets = [assertion, bearer];
  const api = (method, url, { headers = {}, body } = {}) =>
    call(fetch, method, url, { headers: { authorization: `Bearer ${bearer}`, ...headers }, body, secrets });

  const edit = (await api('POST', `${API}/edits`)).json;
  if (!edit?.id) throw new StoreError('Google created no edit.');
  const e = `/edits/${encodeURIComponent(edit.id)}`;
  try {
    const bundles = (await api('GET', `${API}${e}/bundles`)).json?.bundles ?? [];
    const highest = Math.max(0, ...bundles.map((b) => Number(b.versionCode)));
    if (!(highest < code)) {
      throw new StoreError(`${version} (versionCode ${code}) is not newer than the highest bundle in the store (versionCode ${highest}).`);
    }
    const bytes = read(aab);
    const uploaded = (await api('POST', `${UPLOAD}${e}/bundles?uploadType=media`, {
      headers: { 'content-type': 'application/octet-stream' },
      body: bytes,
    })).json;
    if (Number(uploaded?.versionCode) !== code) {
      throw new StoreError(`Google reports versionCode ${uploaded?.versionCode} after the upload, not ${code}.`);
    }
    const sha256 = crypto.createHash('sha256').update(bytes).digest('hex');
    if (uploaded.sha256 !== sha256) throw new StoreError(`Google reports SHA-256 ${uploaded.sha256} for the upload, not ${sha256}.`);
    const release = { name: version, versionCodes: [String(code)], status: 'completed' };
    if (notes) release.releaseNotes = [{ language: LANGUAGE, text: notes }];
    await api('PUT', `${API}${e}/tracks/${track}`, {
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ track, releases: [release] }),
    });
    await api('POST', `${API}${e}:commit`);
  } catch (err) {
    let cleanup = 'The edit this run opened was deleted.';
    try {
      await api('DELETE', `${API}${e}`);
    } catch (cleanupErr) {
      cleanup = `The edit this run opened (${edit.id}) could not be deleted: ${cleanupErr.message}`;
    }
    throw new StoreError(`${err.message} ${cleanup}`);
  }
  return {
    status: 'submitted',
    message: `Submitted ${version} (versionCode ${code}) to the ${track} track in edit ${edit.id}.`,
  };
}

await main(import.meta, { name: 'Google Play', names: NAMES, publish });
