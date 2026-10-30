import fs from 'node:fs';
import path from 'node:path';
import { versionCodeOf } from '../release/version.mjs';
import { call, main, StoreError } from './report.mjs';

export const NAMES = ['AMAZON_CLIENT_ID', 'AMAZON_CLIENT_SECRET', 'AMAZON_APP_ID'];
const TOKEN_URL = 'https://api.amazon.com/auth/o2/token';
const API = 'https://developer.amazon.com/api/appstore/v1/applications';
const LANGUAGE = 'en-US';

async function token(fetch, env) {
  const body = new URLSearchParams({
    grant_type: 'client_credentials',
    client_id: env.AMAZON_CLIENT_ID,
    client_secret: env.AMAZON_CLIENT_SECRET,
    scope: 'appstore::apps:readwrite',
  });
  const { json } = await call(fetch, 'POST', TOKEN_URL, {
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: body.toString(),
    secrets: [env.AMAZON_CLIENT_SECRET],
  });
  if (!json?.access_token) throw new StoreError('Amazon gave no access token.');
  return json.access_token;
}

export async function publish({ env, apk, version, notes = '', fetch, read = fs.readFileSync }) {
  const code = versionCodeOf(version);
  const appId = encodeURIComponent(env.AMAZON_APP_ID);
  const bearer = await token(fetch, env);
  const secrets = [env.AMAZON_CLIENT_SECRET, bearer];
  const api = (method, suffix, { headers = {}, body } = {}) =>
    call(fetch, method, `${API}/${appId}${suffix}`, { headers: { authorization: `Bearer ${bearer}`, ...headers }, body, secrets });
  const etag = (response) => {
    const value = response.headers.get('etag');
    if (!value) throw new StoreError('Amazon gave no ETag.');
    return value;
  };

  let active;
  try {
    active = (await api('GET', '/edits')).json;
  } catch (err) {
    if (!/ answered 404:/.test(err.message)) throw err;
  }
  if (active?.id) {
    throw new StoreError(`An edit (${active.id}, ${active.status ?? 'unknown status'}) is already open and may hold other changes. Commit or delete it in the Amazon Developer Console, then run again.`);
  }

  const edit = (await api('POST', '/edits')).json;
  if (!edit?.id) throw new StoreError('Amazon created no edit.');
  const e = `/edits/${encodeURIComponent(edit.id)}`;
  try {
    const apks = (await api('GET', `${e}/apks`)).json;
    if (!Array.isArray(apks) || apks.length !== 1) {
      throw new StoreError(`The app must have exactly one APK to replace; it has ${Array.isArray(apks) ? apks.length : 'none'}.`);
    }
    const [current] = apks;
    if (!(Number(current.versionCode) < code)) {
      throw new StoreError(`${version} (versionCode ${code}) is not newer than the APK in the store (versionCode ${current.versionCode}).`);
    }
    const apkPath = `${e}/apks/${encodeURIComponent(current.id)}`;
    const replaced = await api('PUT', `${apkPath}/replace`, {
      headers: { 'if-match': etag(await api('GET', apkPath)), 'content-type': 'application/octet-stream', filename: path.basename(apk) },
      body: read(apk),
    });
    if (replaced.json?.versionCode !== undefined && Number(replaced.json.versionCode) !== code) {
      throw new StoreError(`Amazon reports versionCode ${replaced.json.versionCode} after the upload, not ${code}.`);
    }
    if (notes) {
      const listingPath = `${e}/listings/${LANGUAGE}`;
      const listing = await api('GET', listingPath);
      if (!listing.json) throw new StoreError(`Amazon gave no ${LANGUAGE} listing.`);
      await api('PUT', listingPath, {
        headers: { 'if-match': etag(listing), 'content-type': 'application/json' },
        body: JSON.stringify({ ...listing.json, recentChanges: notes }),
      });
    }
    await api('POST', `${e}/validate`);
    await api('POST', `${e}/commit`, { headers: { 'if-match': etag(await api('GET', e)) } });
  } catch (err) {
    let cleanup = 'The edit this run opened was deleted.';
    try {
      await api('DELETE', e, { headers: { 'if-match': etag(await api('GET', e)) } });
    } catch (cleanupErr) {
      cleanup = `The edit this run opened (${edit.id}) could not be deleted: ${cleanupErr.message} Delete it in the Amazon Developer Console before running again.`;
    }
    throw new StoreError(`${err.message} ${cleanup}`);
  }
  return {
    status: 'submitted',
    message: `Submitted ${version} (versionCode ${code}) for review in edit ${edit.id}, replacing the APK and keeping its device targeting${notes ? ', with release notes' : ''}.`,
  };
}

await main(import.meta, { name: 'Amazon Appstore', names: NAMES, publish });
