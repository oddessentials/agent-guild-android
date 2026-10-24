// Checks that tools/release/apk.mjs passes a correctly signed release APK and refuses each kind of
// wrong one. It signs with a disposable key in a temporary folder, deleted afterwards.
//   node tests/release/apk.mjs <unsigned release APK> <its version>
import { execFileSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { disposableKey, keystoreCertSha256, problemsWith, sign } from '../../tools/release/apk.mjs';

const [unsigned, version] = process.argv.slice(2);
if (!unsigned || !version) throw new Error('Usage: node tests/release/apk.mjs <unsigned release APK> <its version>');

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'agent-guild-apk-'));
process.env.RELEASE_KEYSTORE_PASSWORD = crypto.randomBytes(24).toString('hex');
const exe = process.platform === 'win32' ? '.exe' : '';
const javaBin = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'java' + exe) : 'java';
const apksignerJar = path.join(process.env.ANDROID_HOME ?? '', 'build-tools', '37.0.0', 'lib', 'apksigner.jar');

// Signs with apksigner directly, to make APKs that sign() would never make.
function signWith(keystore, out, schemes) {
  execFileSync(javaBin, ['-jar', apksignerJar, 'sign', '--ks', keystore, '--ks-key-alias', 'agent-guild',
    '--ks-pass', 'env:RELEASE_KEYSTORE_PASSWORD', '--v1-signing-enabled', String(schemes.includes('v1')),
    '--v2-signing-enabled', String(schemes.includes('v2')), '--v3-signing-enabled', String(schemes.includes('v3')),
    '--v4-signing-enabled', 'false', '--out', out, unsigned], { stdio: 'pipe' });
  return out;
}

let failed = 0;
function check(name, problems, expected) {
  const ok = expected === null ? problems.length === 0 : problems.some((p) => p.includes(expected));
  if (!ok) failed += 1;
  console.log(`${ok ? 'ok' : 'not ok'}  ${name}`);
  if (!ok) console.log(`        expected ${expected === null ? 'no problems' : `a problem with "${expected}"`}, got: ${JSON.stringify(problems)}`);
}

try {
  const key = path.join(dir, 'key.p12');
  const other = path.join(dir, 'other.p12');
  disposableKey(key);
  disposableKey(other);
  const cert = keystoreCertSha256(key);
  const expected = { version, certSha256: cert };

  const good = path.join(dir, 'good.apk');
  sign(unsigned, good, { keystore: key, version, certSha256: cert });
  check('a release signed by sign() passes', problemsWith(good, expected), null);
  check('an unsigned APK is refused', problemsWith(unsigned, expected), 'apksigner does not verify it');
  check('an APK without a v3 signature is refused', problemsWith(signWith(key, path.join(dir, 'v2.apk'), ['v2']), expected), 'no v3 signature');
  check('an APK without a v2 signature is refused', problemsWith(signWith(key, path.join(dir, 'v3.apk'), ['v3']), expected), 'no v2 signature');
  check('an APK signed by another key is refused', problemsWith(signWith(other, path.join(dir, 'other.apk'), ['v2', 'v3']), expected), 'signed by certificate');
  check('an APK of another version is refused', problemsWith(good, { ...expected, version: '9.8.7' }), 'versionName');

  let refusal = '';
  try {
    sign(unsigned, path.join(dir, 'wrong.apk'), { keystore: other, version, certSha256: cert });
  } catch (err) {
    refusal = err.message;
  }
  check('sign() refuses a keystore whose certificate is not the expected one', refusal ? [refusal] : [], 'Nothing was signed');
  check('... and writes nothing', fs.existsSync(path.join(dir, 'wrong.apk')) ? ['wrong.apk was written'] : [], null);
} finally {
  fs.rmSync(dir, { recursive: true, force: true });
}
process.exitCode = failed > 0 ? 1 : 0;
