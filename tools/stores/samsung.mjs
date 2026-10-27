import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { versionCodeOf } from '../release/version.mjs';
import { call, main, StoreError } from './report.mjs';

export const NAMES = ['SAMSUNG_SERVICE_ACCOUNT_ID', 'SAMSUNG_PRIVATE_KEY', 'SAMSUNG_CONTENT_ID'];
const API = 'https://devapi.samsungapps.com';
const UPLOAD_HOST = 'seller.samsungapps.com';

const base64url = (data) => Buffer.from(data).toString('base64url');

export function jwt({ serviceAccountId, privateKey, now }) {
  const iat = Math.floor(now / 1000);
  const head = base64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  const claims = base64url(JSON.stringify({ iss: serviceAccountId, scopes: ['publishing'], iat, exp: iat + 1200 }));
  const signature = crypto.sign('sha256', Buffer.from(`${head}.${claims}`), privateKey);
  return `${head}.${claims}.${base64url(signature)}`;
}

export async function publish({ env, apk, version, fetch, read = fs.readFileSync, now = Date.now() }) {
  const code = versionCodeOf(version);
  const contentId = env.SAMSUNG_CONTENT_ID;
  if (!/^[0-9]{12}$/.test(contentId)) throw new StoreError('SAMSUNG_CONTENT_ID must be the 12-digit content ID.');
  const signed = jwt({ serviceAccountId: env.SAMSUNG_SERVICE_ACCOUNT_ID, privateKey: env.SAMSUNG_PRIVATE_KEY.replace(/\\n/g, '\n'), now });
  const auth = await call(fetch, 'POST', `${API}/auth/accessToken`, {
    headers: { authorization: `Bearer ${signed}`, 'content-type': 'application/json' },
    secrets: [signed],
  });
  const bearer = auth.json?.createdItem?.accessToken;
  if (!auth.json?.ok || !bearer) throw new StoreError('Samsung gave no access token.');
  const secrets = [signed, bearer];
  const headers = { authorization: `Bearer ${bearer}`, 'service-account-id': env.SAMSUNG_SERVICE_ACCOUNT_ID };
  const post = (suffix, body) => call(fetch, 'POST', `${API}${suffix}`, {
    headers: { ...headers, 'content-type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body), secrets,
  });

  const info = (await call(fetch, 'GET', `${API}/seller/contentInfo?contentId=${contentId}`, { headers, secrets })).json;
  const entries = Array.isArray(info) ? info : info ? [info] : [];
  const statuses = entries.map((entry) => entry.contentStatus).join(', ') || 'none';
  if (entries.length !== 1 || entries[0].contentStatus !== 'FOR_SALE') {
    throw new StoreError(`The app must be For Sale with no update in progress; its status is ${statuses}. Finish or cancel the open update in Seller Portal, then run again.`);
  }
  const [sale] = entries;
  const binaries = Array.isArray(sale.binaryList) ? sale.binaryList : [];
  if (binaries.length === 0) throw new StoreError('The app on sale lists no binary.');
  const latest = binaries.reduce((a, b) => (Number(b.versionCode) > Number(a.versionCode) ? b : a));
  if (!(Number(latest.versionCode) < code)) {
    throw new StoreError(`${version} (versionCode ${code}) is not newer than the binary in the store (versionCode ${latest.versionCode}).`);
  }
  if (latest.binarySeq === undefined || latest.binarySeq === null || latest.binarySeq === '') {
    throw new StoreError('The binary on sale has no binarySeq, so its device settings cannot be copied.');
  }
  if (latest.gms !== 'Y' && latest.gms !== 'N') throw new StoreError(`The binary on sale has gms "${latest.gms}", not Y or N.`);
  for (const field of ['defaultLanguageCode', 'paid', 'privatePolicyURLYN', 'publicationType']) {
    if (sale[field] === undefined || sale[field] === null) throw new StoreError(`The app on sale has no ${field}.`);
  }

  const session = (await post('/seller/createUploadSessionId')).json;
  if (!session?.sessionId || !session?.url) throw new StoreError('Samsung gave no upload session.');
  const uploadUrl = new URL(session.url);
  if (uploadUrl.protocol !== 'https:' || uploadUrl.hostname !== UPLOAD_HOST) {
    throw new StoreError(`Samsung's upload URL is on ${uploadUrl.hostname}, not ${UPLOAD_HOST}.`);
  }
  const form = new FormData();
  form.append('sessionId', session.sessionId);
  form.append('file', new Blob([read(apk)], { type: 'application/vnd.android.package-archive' }), path.basename(apk));
  const upload = (await call(fetch, 'POST', uploadUrl.href, { headers, body: form, secrets })).json;
  if (!upload?.fileKey || upload.errorCode) throw new StoreError(`The upload gave no file key: ${upload?.errorMsg ?? 'no details'}.`);

  const update = (await post('/seller/contentUpdate', {
    contentId,
    defaultLanguageCode: sale.defaultLanguageCode,
    paid: sale.paid,
    privatePolicyURLYN: sale.privatePolicyURLYN,
    publicationType: sale.publicationType,
  })).json;
  if (update?.errorCode) throw new StoreError(`contentUpdate failed: ${update.errorMsg ?? update.errorCode}.`);

  const pending = 'The update stays open in Seller Portal as Updating; finish or cancel it there before running again.';
  const binary = (await post('/seller/v2/content/binary', {
    contentId,
    gms: latest.gms,
    filekey: upload.fileKey,
    binarySeqForDeviceInfo: String(latest.binarySeq),
  }).catch((err) => { throw new StoreError(`${err.message} ${pending}`); })).json;
  if (binary?.resultCode !== '0000') throw new StoreError(`Adding the binary failed: ${binary?.resultMessage ?? 'no details'}. ${pending}`);

  await post('/seller/contentSubmit', { contentId }).catch((err) => { throw new StoreError(`${err.message} ${pending}`); });
  return {
    status: 'submitted',
    message: `Submitted ${version} (versionCode ${code}) for review as binary ${binary.data?.binarySeq ?? 'unknown'}, with the device settings of binary ${latest.binarySeq}.`,
  };
}

await main(import.meta, { name: 'Samsung Galaxy Store', names: NAMES, publish });
