import assert from 'node:assert/strict';
import { test } from 'node:test';
import { publish } from '../../tools/stores/meta.mjs';

const env = { META_APP_ID: '1325496170657270', META_APP_SECRET: 'meta-secret', META_CLI: '/bin/ovr' };

function runner(result) {
  const calls = [];
  const run = async (file, args) => {
    calls.push({ file, args });
    return result;
  };
  return { run, calls };
}

test('uploads a draft to the Store channel and reports the manual submission', async () => {
  const { run, calls } = runner({ code: 0, output: 'Uploading...\n* Created Build ID: 987654\n' });
  const result = await publish({ env, apk: '/tmp/agent-guild-1.2.3.apk', version: '1.2.3', run });
  assert.equal(result.status, 'manual action required');
  assert.match(result.message, /draft build 987654/);
  assert.match(result.message, /not submitted/);
  assert.match(result.message, /applications\/1325496170657270\//);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].file, '/bin/ovr');
  const args = calls[0].args;
  const flag = (name) => args[args.indexOf(name) + 1];
  assert.equal(args[0], 'upload-quest-build');
  assert.equal(flag('--channel'), 'store');
  assert.equal(flag('--age-group'), 'TEENS_AND_ADULTS');
  assert.equal(flag('--apk'), '/tmp/agent-guild-1.2.3.apk');
  assert.ok(args.includes('--draft'));
  assert.ok(args.includes('--wait-for-postprocessing'));
});

test('exit code 0 without a created build is a failure', async () => {
  const { run } = runner({ code: 0, output: 'Usage: upload-quest-build\nInvalid JSON config file' });
  await assert.rejects(publish({ env, apk: 'a.apk', version: '1.2.3', run }), /did not create a build \(exit code 0\)/);
});

test('a failed upload is a failure, and the secret never appears in it', async () => {
  const { run } = runner({ code: 1, output: 'ERROR: app secret meta-secret is invalid\n' });
  await assert.rejects(publish({ env, apk: 'a.apk', version: '1.2.3', run }), (err) => {
    assert.match(err.message, /exit code 1/);
    assert.doesNotMatch(err.message, /meta-secret/);
    return true;
  });
});

test('failed postprocessing after a created build is a failure', async () => {
  const { run } = runner({ code: 1, output: 'Created Build ID: 987654\nMalware check failed\n' });
  await assert.rejects(publish({ env, apk: 'a.apk', version: '1.2.3', run }), /did not create a build \(exit code 1\)/);
});

test('a non-numeric app ID stops before any upload', async () => {
  const { run, calls } = runner({ code: 0, output: '' });
  await assert.rejects(publish({ env: { ...env, META_APP_ID: 'abc' }, apk: 'a.apk', version: '1.2.3', run }), /numeric app ID/);
  assert.equal(calls.length, 0);
});

test('release notes pass to the utility, with line breaks as \\n', async () => {
  const { run, calls } = runner({ code: 0, output: 'Created Build ID: 987654\n' });
  await publish({ env, apk: 'a.apk', version: '1.2.3', notes: '• One\n• Two', run });
  const args = calls[0].args;
  assert.equal(args[args.indexOf('--notes') + 1], '• One\\n• Two');
});

test('no release notes means no --notes', async () => {
  const { run, calls } = runner({ code: 0, output: 'Created Build ID: 987654\n' });
  await publish({ env, apk: 'a.apk', version: '1.2.3', run });
  assert.ok(!calls[0].args.includes('--notes'));
});
