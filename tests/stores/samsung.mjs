import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import { test } from 'node:test';
import { jwt, publish } from '../../tools/stores/samsung.mjs';
import { fakeFetch } from './fake.mjs';

const { privateKey, publicKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
const pem = privateKey.export({ type: 'pkcs8', format: 'pem' });
const env = { SAMSUNG_SERVICE_ACCOUNT_ID: 'sa-1', SAMSUNG_PRIVATE_KEY: pem, SAMSUNG_CONTENT_ID: '000007654321' };
const D = 'devapi.samsungapps.com';
const now = Date.UTC(2026, 9, 27, 12, 0, 0);
const read = () => Buffer.from('apk bytes');

const sale = {
  contentId: '000007654321',
  contentStatus: 'FOR_SALE',
  defaultLanguageCode: 'ENG',
  paid: 'N',
  privatePolicyURLYN: 'Y',
  publicationType: '01',
  binaryList: [
    { binarySeq: '1', versionCode: '8000', gms: 'N' },
    { binarySeq: '2', versionCode: '9000', gms: 'N' },
  ],
};

function routes(overrides = {}) {
  return {
    [`POST ${D}/auth/accessToken`]: { body: { ok: true, createdItem: { accessToken: 'access-token' } } },
    [`GET ${D}/seller/contentInfo`]: { body: [sale] },
    [`POST ${D}/seller/createUploadSessionId`]: { body: { url: 'https://seller.samsungapps.com/galaxyapi/fileUpload', sessionId: 's-1' } },
    'POST seller.samsungapps.com/galaxyapi/fileUpload': { body: { fileKey: 'fk-1', fileName: 'a.apk', errorCode: null, errorMsg: null } },
    [`POST ${D}/seller/contentUpdate`]: { body: { ctntId: '000007654321', contentStatus: 'REGISTERING', httpStatus: 'OK', errorCode: null, errorMsg: null } },
    [`POST ${D}/seller/v2/content/binary`]: { body: { resultCode: '0000', resultMessage: 'Ok', data: { binarySeq: '3' } } },
    [`POST ${D}/seller/contentSubmit`]: { status: 204 },
    ...overrides,
  };
}

test('the JWT is RS256, signed by the service account key, and lives 20 minutes', () => {
  const token = jwt({ serviceAccountId: 'sa-1', privateKey: pem, now });
  const [head, claims, signature] = token.split('.');
  assert.deepEqual(JSON.parse(Buffer.from(head, 'base64url')), { alg: 'RS256', typ: 'JWT' });
  const c = JSON.parse(Buffer.from(claims, 'base64url'));
  assert.deepEqual(c, { iss: 'sa-1', scopes: ['publishing'], iat: now / 1000, exp: now / 1000 + 1200 });
  assert.ok(crypto.verify('sha256', Buffer.from(`${head}.${claims}`), publicKey, Buffer.from(signature, 'base64url')));
});

test('uploads, opens the update, adds the binary with the old device settings, and submits', async () => {
  const f = fakeFetch(routes());
  const result = await publish({ env, apk: '/tmp/agent-guild-1.2.3.apk', version: '1.2.3', fetch: f.fetch, read, now });
  assert.equal(result.status, 'submitted');
  assert.deepEqual(f.keys(), [
    `POST ${D}/auth/accessToken`, `GET ${D}/seller/contentInfo`, `POST ${D}/seller/createUploadSessionId`,
    'POST seller.samsungapps.com/galaxyapi/fileUpload', `POST ${D}/seller/contentUpdate`, `POST ${D}/seller/v2/content/binary`,
    `POST ${D}/seller/contentSubmit`,
  ]);
  for (const call of f.calls.slice(1)) {
    assert.equal(call.init.headers.authorization, 'Bearer access-token');
    assert.equal(call.init.headers['service-account-id'], 'sa-1');
  }
  assert.equal(f.calls[1].url.searchParams.get('contentId'), '000007654321');
  const upload = f.calls[3].init.body;
  assert.equal(upload.get('sessionId'), 's-1');
  assert.equal(upload.get('file').name, 'agent-guild-1.2.3.apk');
  assert.deepEqual(JSON.parse(f.calls[4].init.body), {
    contentId: '000007654321', defaultLanguageCode: 'ENG', paid: 'N', privatePolicyURLYN: 'Y', publicationType: '01',
  });
  assert.deepEqual(JSON.parse(f.calls[5].init.body), {
    contentId: '000007654321', gms: 'N', filekey: 'fk-1', binarySeqForDeviceInfo: '2',
  });
  assert.deepEqual(JSON.parse(f.calls[6].init.body), { contentId: '000007654321' });
});

test('an update already in progress stops before any change', async () => {
  const f = fakeFetch(routes({ [`GET ${D}/seller/contentInfo`]: { body: [sale, { ...sale, contentStatus: 'REGISTERING' }] } }));
  await assert.rejects(publish({ env, apk: 'a.apk', version: '1.2.3', fetch: f.fetch, read, now }), /no update in progress; its status is FOR_SALE, REGISTERING/);
  assert.equal(f.calls.length, 2);
});

test('a version that is not newer stops before any change', async () => {
  const f = fakeFetch(routes());
  await assert.rejects(publish({ env, apk: 'a.apk', version: '0.9.0', fetch: f.fetch, read, now }), /not newer.*versionCode 9000/);
  assert.equal(f.calls.length, 2);
});

test('a missing binarySeq stops before any change, since device settings would be lost', async () => {
  const f = fakeFetch(routes({ [`GET ${D}/seller/contentInfo`]: { body: [{ ...sale, binaryList: [{ versionCode: '9000', gms: 'N' }] }] } }));
  await assert.rejects(publish({ env, apk: 'a.apk', version: '1.2.3', fetch: f.fetch, read, now }), /no binarySeq/);
  assert.equal(f.calls.length, 2);
});

test('an authentication failure stops at once', async () => {
  const f = fakeFetch(routes({ [`POST ${D}/auth/accessToken`]: { status: 401, body: '{"ok":false}' } }));
  await assert.rejects(publish({ env, apk: 'a.apk', version: '1.2.3', fetch: f.fetch, read, now }), /answered 401/);
  assert.equal(f.calls.length, 1);
});

test('an upload URL on another host is refused, so the token goes nowhere else', async () => {
  const f = fakeFetch(routes({ [`POST ${D}/seller/createUploadSessionId`]: { body: { url: 'https://evil.example/upload', sessionId: 's' } } }));
  await assert.rejects(publish({ env, apk: 'a.apk', version: '1.2.3', fetch: f.fetch, read, now }), /evil\.example, not seller\.samsungapps\.com/);
  assert.ok(!f.keys().some((k) => k.includes('evil')));
});

test('an upload failure stops before the update is opened', async () => {
  const f = fakeFetch(routes({ 'POST seller.samsungapps.com/galaxyapi/fileUpload': { body: { errorCode: 'E1', errorMsg: 'too big' } } }));
  await assert.rejects(publish({ env, apk: 'a.apk', version: '1.2.3', fetch: f.fetch, read, now }), /no file key: too big/);
  assert.ok(!f.keys().some((k) => k.endsWith('/contentUpdate')));
});

test('a failed binary step fails, says the update stays open, and does not submit', async () => {
  const f = fakeFetch(routes({ [`POST ${D}/seller/v2/content/binary`]: { body: { resultCode: '4122', resultMessage: 'Binary Upload Key error' } } }));
  await assert.rejects(publish({ env, apk: 'a.apk', version: '1.2.3', fetch: f.fetch, read, now }), /Binary Upload Key error.*stays open in Seller Portal/s);
  assert.ok(!f.keys().some((k) => k.endsWith('/contentSubmit')));
});

test('a failed submit fails and says the update stays open', async () => {
  const f = fakeFetch(routes({ [`POST ${D}/seller/contentSubmit`]: { status: 400, body: '{"errorCode":"4128"}' } }));
  await assert.rejects(publish({ env, apk: 'a.apk', version: '1.2.3', fetch: f.fetch, read, now }), /answered 400.*stays open/s);
});

test('an escaped private key from a secret store still signs', async () => {
  const f = fakeFetch(routes());
  const escaped = { ...env, SAMSUNG_PRIVATE_KEY: pem.replace(/\n/g, '\\n') };
  const result = await publish({ env: escaped, apk: 'a.apk', version: '1.2.3', fetch: f.fetch, read, now });
  assert.equal(result.status, 'submitted');
});

test('release notes go in the update as its new features', async () => {
  const f = fakeFetch(routes());
  await publish({ env, apk: 'a.apk', version: '1.2.3', notes: '• Faster sign-in', fetch: f.fetch, read, now });
  const update = f.calls.find((c) => c.key === `POST ${D}/seller/contentUpdate`);
  assert.equal(JSON.parse(update.init.body).newFeature, '• Faster sign-in');
});
