import assert from 'node:assert/strict';
import { test } from 'node:test';
import { publish } from '../../tools/stores/amazon.mjs';
import { fakeFetch } from './fake.mjs';

const env = { AMAZON_CLIENT_ID: 'id', AMAZON_CLIENT_SECRET: 'amazon-secret', AMAZON_APP_ID: 'amzn1.devportal.mobileapp.abc' };
const A = 'developer.amazon.com/api/appstore/v1/applications/amzn1.devportal.mobileapp.abc';
const E = `${A}/edits/e1`;
const read = () => Buffer.from('apk bytes');

function routes(overrides = {}) {
  return {
    'POST api.amazon.com/auth/o2/token': { body: { access_token: 'bearer-token' } },
    [`GET ${A}/edits`]: { body: '' },
    [`POST ${A}/edits`]: { body: { id: 'e1', status: 'IN_PROGRESS' } },
    [`GET ${E}/apks`]: { body: [{ id: 'apk1', versionCode: 9000 }] },
    [`GET ${E}/apks/apk1`]: { body: { id: 'apk1' }, headers: { etag: '"apk-etag"' } },
    [`PUT ${E}/apks/apk1/replace`]: { body: { id: 'apk1', versionCode: 1002003 } },
    [`POST ${E}/validate`]: { body: { id: 'e1' } },
    [`GET ${E}`]: { body: { id: 'e1' }, headers: { etag: '"edit-etag"' } },
    [`POST ${E}/commit`]: { body: { id: 'e1', status: 'SUBMITTED' } },
    [`DELETE ${E}`]: { status: 204 },
    ...overrides,
  };
}

test('replaces the APK in a new edit and commits it', async () => {
  const f = fakeFetch(routes());
  const result = await publish({ env, apk: '/tmp/agent-guild-1.2.3.apk', version: '1.2.3', fetch: f.fetch, read });
  assert.equal(result.status, 'submitted');
  assert.deepEqual(f.keys(), [
    'POST api.amazon.com/auth/o2/token', `GET ${A}/edits`, `POST ${A}/edits`, `GET ${E}/apks`, `GET ${E}/apks/apk1`,
    `PUT ${E}/apks/apk1/replace`, `POST ${E}/validate`, `GET ${E}`, `POST ${E}/commit`,
  ]);
  const token = new URLSearchParams(f.calls[0].init.body);
  assert.equal(token.get('grant_type'), 'client_credentials');
  assert.equal(token.get('scope'), 'appstore::apps:readwrite');
  const replace = f.calls[5].init;
  assert.equal(replace.headers['if-match'], '"apk-etag"');
  assert.equal(replace.headers['content-type'], 'application/octet-stream');
  assert.equal(replace.headers.filename, 'agent-guild-1.2.3.apk');
  assert.equal(replace.headers.authorization, 'Bearer bearer-token');
  assert.equal(f.calls[8].init.headers['if-match'], '"edit-etag"');
});

test('an open edit is left untouched and the run fails', async () => {
  const f = fakeFetch(routes({ [`GET ${A}/edits`]: { body: { id: 'other', status: 'IN_PROGRESS' } } }));
  await assert.rejects(publish({ env, apk: 'a.apk', version: '1.2.3', fetch: f.fetch, read }), /edit \(other, IN_PROGRESS\) is already open/);
  assert.ok(!f.keys().some((k) => k.startsWith('POST') && k.endsWith('/edits')));
  assert.ok(!f.keys().some((k) => k.startsWith('DELETE')));
});

test('a 404 for the active edit means there is none', async () => {
  const f = fakeFetch(routes({ [`GET ${A}/edits`]: { status: 404, body: 'none' } }));
  const result = await publish({ env, apk: 'a.apk', version: '1.2.3', fetch: f.fetch, read });
  assert.equal(result.status, 'submitted');
});

test('a version that is not newer is rejected, and the new edit is deleted', async () => {
  const f = fakeFetch(routes({ [`GET ${E}/apks`]: { body: [{ id: 'apk1', versionCode: 1002003 }] } }));
  await assert.rejects(publish({ env, apk: 'a.apk', version: '1.2.3', fetch: f.fetch, read }), /not newer.*versionCode 1002003.*edit this run opened was deleted/s);
  assert.ok(!f.keys().some((k) => k.includes('/replace')));
  assert.equal(f.keys().at(-1), `DELETE ${E}`);
  assert.equal(f.calls.at(-1).init.headers['if-match'], '"edit-etag"');
});

test('more than one APK is refused', async () => {
  const f = fakeFetch(routes({ [`GET ${E}/apks`]: { body: [{ id: 'a', versionCode: 1 }, { id: 'b', versionCode: 2 }] } }));
  await assert.rejects(publish({ env, apk: 'a.apk', version: '1.2.3', fetch: f.fetch, read }), /exactly one APK.*has 2/);
});

test('an authentication failure stops before anything else and hides the secret', async () => {
  const f = fakeFetch(routes({ 'POST api.amazon.com/auth/o2/token': { status: 401, body: 'bad client amazon-secret' } }));
  await assert.rejects(publish({ env, apk: 'a.apk', version: '1.2.3', fetch: f.fetch, read }), (err) => {
    assert.match(err.message, /answered 401/);
    assert.doesNotMatch(err.message, /amazon-secret/);
    return true;
  });
  assert.equal(f.calls.length, 1);
});

test('an upload failure deletes the edit and fails', async () => {
  const f = fakeFetch(routes({ [`PUT ${E}/apks/apk1/replace`]: { status: 500, body: 'boom' } }));
  await assert.rejects(publish({ env, apk: 'a.apk', version: '1.2.3', fetch: f.fetch, read }), /answered 500: boom.*deleted/s);
  assert.ok(!f.keys().some((k) => k.endsWith('/commit')));
});

test('validation errors fail without committing', async () => {
  const f = fakeFetch(routes({ [`POST ${E}/validate`]: { status: 403, body: '{"errors":["bad"]}' } }));
  await assert.rejects(publish({ env, apk: 'a.apk', version: '1.2.3', fetch: f.fetch, read }), /answered 403/);
  assert.ok(!f.keys().some((k) => k.endsWith('/commit')));
});

test('a failed cleanup is reported so the edit can be removed by hand', async () => {
  const f = fakeFetch(routes({
    [`PUT ${E}/apks/apk1/replace`]: { status: 500, body: 'boom' },
    [`DELETE ${E}`]: { status: 412, body: 'precondition' },
  }));
  await assert.rejects(publish({ env, apk: 'a.apk', version: '1.2.3', fetch: f.fetch, read }), /\(e1\) could not be deleted/);
});

test('a mismatched versionCode after upload fails', async () => {
  const f = fakeFetch(routes({ [`PUT ${E}/apks/apk1/replace`]: { body: { id: 'apk1', versionCode: 9001 } } }));
  await assert.rejects(publish({ env, apk: 'a.apk', version: '1.2.3', fetch: f.fetch, read }), /reports versionCode 9001/);
});

test('release notes replace the en-US listing\'s recent changes, keeping the rest of it', async () => {
  const listing = { language: 'en-US', title: 'Agent Guild: Remote', shortDescription: 's', fullDescription: 'f', recentChanges: 'old', featureBullets: ['b'], keywords: ['k'] };
  const f = fakeFetch(routes({
    [`GET ${E}/listings/en-US`]: { body: listing, headers: { etag: '"listing-etag"' } },
    [`PUT ${E}/listings/en-US`]: { body: {} },
  }));
  const result = await publish({ env, apk: 'a.apk', version: '1.2.3', notes: '• Faster sign-in', fetch: f.fetch, read });
  assert.match(result.message, /with release notes/);
  const keys = f.keys();
  assert.ok(keys.indexOf(`PUT ${E}/apks/apk1/replace`) < keys.indexOf(`PUT ${E}/listings/en-US`));
  assert.ok(keys.indexOf(`PUT ${E}/listings/en-US`) < keys.indexOf(`POST ${E}/validate`));
  const put = f.calls.find((c) => c.key === `PUT ${E}/listings/en-US`).init;
  assert.equal(put.headers['if-match'], '"listing-etag"');
  assert.deepEqual(JSON.parse(put.body), { ...listing, recentChanges: '• Faster sign-in' });
});

test('a listing update that fails deletes the edit', async () => {
  const f = fakeFetch(routes({
    [`GET ${E}/listings/en-US`]: { body: { language: 'en-US' }, headers: { etag: '"l"' } },
    [`PUT ${E}/listings/en-US`]: { status: 412, body: '{"errors":["precondition"]}' },
  }));
  await assert.rejects(publish({ env, apk: 'a.apk', version: '1.2.3', notes: 'n', fetch: f.fetch, read }), /answered 412.*was deleted/s);
  assert.ok(!f.keys().includes(`POST ${E}/commit`));
});
