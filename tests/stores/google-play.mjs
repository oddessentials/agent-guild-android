import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import { test } from 'node:test';
import { jwt, publish, serviceAccount } from '../../tools/stores/google-play.mjs';
import { fakeFetch } from './fake.mjs';

const { privateKey, publicKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
const pem = privateKey.export({ type: 'pkcs8', format: 'pem' });
const account = { type: 'service_account', client_email: 'ci@project.iam.gserviceaccount.com', private_key: pem };
const env = { GOOGLE_PLAY_SERVICE_ACCOUNT_JSON: JSON.stringify(account) };
const now = Date.UTC(2026, 9, 29, 12, 0, 0);
const bytes = Buffer.from('aab bytes');
const read = () => bytes;
const sha256 = crypto.createHash('sha256').update(bytes).digest('hex');
const G = 'androidpublisher.googleapis.com';
const A = `${G}/androidpublisher/v3/applications/com.oddessentials.agentguild`;
const U = `${G}/upload/androidpublisher/v3/applications/com.oddessentials.agentguild`;

function routes(overrides = {}) {
  return {
    'POST oauth2.googleapis.com/token': { body: { access_token: 'access-token' } },
    [`POST ${A}/edits`]: { body: { id: 'e-1' } },
    [`GET ${A}/edits/e-1/bundles`]: { body: { bundles: [{ versionCode: 9000 }, { versionCode: 8000 }] } },
    [`POST ${U}/edits/e-1/bundles`]: { body: { versionCode: 1002003, sha256 } },
    [`PUT ${A}/edits/e-1/tracks/production`]: { body: {} },
    [`POST ${A}/edits/e-1:commit`]: { body: { id: 'e-1' } },
    [`DELETE ${A}/edits/e-1`]: { status: 204 },
    ...overrides,
  };
}

test('the JWT is RS256, signed by the service account key, for the androidpublisher scope, and lives an hour', () => {
  const token = jwt({ ...serviceAccount(env.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON), now });
  const [head, claims, signature] = token.split('.');
  assert.deepEqual(JSON.parse(Buffer.from(head, 'base64url')), { alg: 'RS256', typ: 'JWT' });
  assert.deepEqual(JSON.parse(Buffer.from(claims, 'base64url')), {
    iss: 'ci@project.iam.gserviceaccount.com',
    scope: 'https://www.googleapis.com/auth/androidpublisher',
    aud: 'https://oauth2.googleapis.com/token',
    iat: now / 1000,
    exp: now / 1000 + 3600,
  });
  assert.ok(crypto.verify('sha256', Buffer.from(`${head}.${claims}`), publicKey, Buffer.from(signature, 'base64url')));
});

test('opens an edit, uploads the bundle, releases it to production, and commits', async () => {
  const f = fakeFetch(routes());
  const result = await publish({ env, apk: 'agent-guild-1.2.3.aab', version: '1.2.3', fetch: f.fetch, read, now });
  assert.equal(result.status, 'submitted');
  assert.deepEqual(f.keys(), [
    'POST oauth2.googleapis.com/token', `POST ${A}/edits`, `GET ${A}/edits/e-1/bundles`, `POST ${U}/edits/e-1/bundles`,
    `PUT ${A}/edits/e-1/tracks/production`, `POST ${A}/edits/e-1:commit`,
  ]);
  const token = new URLSearchParams(f.calls[0].init.body);
  assert.equal(token.get('grant_type'), 'urn:ietf:params:oauth:grant-type:jwt-bearer');
  for (const call of f.calls.slice(1)) assert.equal(call.init.headers.authorization, 'Bearer access-token');
  assert.equal(f.calls[3].url.searchParams.get('uploadType'), 'media');
  assert.equal(f.calls[3].init.body, bytes);
  assert.deepEqual(JSON.parse(f.calls[4].init.body), {
    track: 'production', releases: [{ name: '1.2.3', versionCodes: ['1002003'], status: 'completed' }],
  });
});

test('GOOGLE_PLAY_TRACK picks the track', async () => {
  const f = fakeFetch(routes({ [`PUT ${A}/edits/e-1/tracks/internal`]: { body: {} } }));
  await publish({ env: { ...env, GOOGLE_PLAY_TRACK: 'internal' }, apk: 'a.aab', version: '1.2.3', fetch: f.fetch, read, now });
  assert.ok(f.keys().includes(`PUT ${A}/edits/e-1/tracks/internal`));
});

test('an unknown track stops before any request', async () => {
  const f = fakeFetch(routes());
  await assert.rejects(publish({ env: { ...env, GOOGLE_PLAY_TRACK: 'staging' }, apk: 'a.aab', version: '1.2.3', fetch: f.fetch, read, now }), /must be one of/);
  assert.equal(f.calls.length, 0);
});

test('a secret that is not a service account key stops before any request', async () => {
  const f = fakeFetch(routes());
  await assert.rejects(publish({ env: { GOOGLE_PLAY_SERVICE_ACCOUNT_JSON: '{"type":"user"}' }, apk: 'a.aab', version: '1.2.3', fetch: f.fetch, read, now }), /service account key/);
  assert.equal(f.calls.length, 0);
});

test('a version that is not newer deletes the edit before uploading', async () => {
  const f = fakeFetch(routes());
  await assert.rejects(publish({ env, apk: 'a.aab', version: '0.9.0', fetch: f.fetch, read, now }), /not newer.*versionCode 9000.*was deleted/s);
  assert.ok(!f.keys().includes(`POST ${U}/edits/e-1/bundles`));
  assert.equal(f.keys().at(-1), `DELETE ${A}/edits/e-1`);
});

test('an authentication failure stops at once', async () => {
  const f = fakeFetch(routes({ 'POST oauth2.googleapis.com/token': { status: 400, body: '{"error":"invalid_grant"}' } }));
  await assert.rejects(publish({ env, apk: 'a.aab', version: '1.2.3', fetch: f.fetch, read, now }), /answered 400/);
  assert.equal(f.calls.length, 1);
});

test('an upload failure deletes the edit and never touches the track', async () => {
  const f = fakeFetch(routes({ [`POST ${U}/edits/e-1/bundles`]: { status: 403, body: '{"error":"forbidden"}' } }));
  await assert.rejects(publish({ env, apk: 'a.aab', version: '1.2.3', fetch: f.fetch, read, now }), /answered 403.*was deleted/s);
  assert.ok(!f.keys().some((k) => k.includes('/tracks/')));
  assert.equal(f.keys().at(-1), `DELETE ${A}/edits/e-1`);
});

test('an upload that Google reports with other bytes deletes the edit', async () => {
  const f = fakeFetch(routes({ [`POST ${U}/edits/e-1/bundles`]: { body: { versionCode: 1002003, sha256: '0'.repeat(64) } } }));
  await assert.rejects(publish({ env, apk: 'a.aab', version: '1.2.3', fetch: f.fetch, read, now }), /SHA-256.*was deleted/s);
  assert.ok(!f.keys().some((k) => k.includes('/tracks/')));
});

test('a commit failure deletes the edit', async () => {
  const f = fakeFetch(routes({ [`POST ${A}/edits/e-1:commit`]: { status: 400, body: '{"error":"incomplete"}' } }));
  await assert.rejects(publish({ env, apk: 'a.aab', version: '1.2.3', fetch: f.fetch, read, now }), /answered 400.*was deleted/s);
  assert.equal(f.keys().at(-1), `DELETE ${A}/edits/e-1`);
});

test('an escaped private key from a secret store still signs', async () => {
  const f = fakeFetch(routes());
  const escaped = { GOOGLE_PLAY_SERVICE_ACCOUNT_JSON: JSON.stringify({ ...account, private_key: pem.replace(/\n/g, '\\n') }) };
  const result = await publish({ env: escaped, apk: 'a.aab', version: '1.2.3', fetch: f.fetch, read, now });
  assert.equal(result.status, 'submitted');
});
