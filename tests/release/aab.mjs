import { execFileSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { problemsWith, sign } from '../../tools/release/aab.mjs';
import { disposableKey, keystoreCertSha256 } from '../../tools/release/apk.mjs';

const [unsigned, version] = process.argv.slice(2);
if (!unsigned || !version) throw new Error('Usage: node tests/release/aab.mjs <unsigned release bundle> <its version>');

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'agent-guild-aab-'));
process.env.RELEASE_KEYSTORE_PASSWORD = crypto.randomBytes(24).toString('hex');
const exe = process.platform === 'win32' ? '.exe' : '';
const jarsigner = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'jarsigner' + exe) : 'jarsigner';

function signWith(keystore, input, out, sigfile) {
  execFileSync(jarsigner, ['-keystore', keystore, '-storetype', 'PKCS12', '-storepass:env', 'RELEASE_KEYSTORE_PASSWORD',
    '-digestalg', 'SHA-256', '-sigalg', 'SHA256withRSA', '-sigfile', sigfile, '-signedjar', out, input, 'agent-guild'], { stdio: 'pipe' });
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

  const good = path.join(dir, 'good.aab');
  sign(unsigned, good, { keystore: key, version, certSha256: cert });
  check('a bundle signed by sign() passes', problemsWith(good, expected), null);
  check('an unsigned bundle is refused', problemsWith(unsigned, expected), 'jarsigner does not verify it');
  check('a bundle signed by another key is refused', problemsWith(signWith(other, unsigned, path.join(dir, 'other.aab'), 'OTHER'), expected), 'signed by certificate');
  check('a bundle with two signers is refused', problemsWith(signWith(other, good, path.join(dir, 'two.aab'), 'SECOND'), expected), '2 signers');
  check('a bundle of another version is refused', problemsWith(good, { ...expected, version: '9.8.7' }), 'versionName');

  let refusal = '';
  try {
    sign(unsigned, path.join(dir, 'wrong.aab'), { keystore: other, version, certSha256: cert });
  } catch (err) {
    refusal = err.message;
  }
  check('sign() refuses a keystore whose certificate is not the expected one', refusal ? [refusal] : [], 'Nothing was signed');
  check('... and writes nothing', fs.existsSync(path.join(dir, 'wrong.aab')) ? ['wrong.aab was written'] : [], null);
} finally {
  fs.rmSync(dir, { recursive: true, force: true });
}
process.exitCode = failed > 0 ? 1 : 0;
