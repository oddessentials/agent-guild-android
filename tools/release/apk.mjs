// Signs the release APK and checks a signed one. CI and the release workflow both use it.
//
//   node tools/release/apk.mjs disposable-key <keystore>
//   node tools/release/apk.mjs sign <unsigned.apk> <signed.apk> --keystore <keystore> --version <version> [--cert-sha256 <hex>]
//   node tools/release/apk.mjs verify <signed.apk> --version <version> --cert-sha256 <hex>
//
// The keystore is PKCS12 with one key, alias agent-guild, whose password is RELEASE_KEYSTORE_PASSWORD.
// The password goes to keytool and apksigner by the variable's name, never on a command line.
// Needs Java, and the Android SDK's build-tools 37.0.0 under ANDROID_HOME.
import { execFileSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { versionCodeOf } from './version.mjs';

export const PACKAGE = 'com.oddessentials.agentguild';
const ALIAS = 'agent-guild';
const PASSWORD = 'RELEASE_KEYSTORE_PASSWORD';
const exe = process.platform === 'win32' ? '.exe' : '';

function buildTools() {
  if (!process.env.ANDROID_HOME) throw new Error('ANDROID_HOME must name the Android SDK');
  return path.join(process.env.ANDROID_HOME, 'build-tools', '37.0.0');
}

function java(tool) {
  return process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', tool + exe) : tool;
}

function run(file, args, name = path.basename(file)) {
  try {
    return execFileSync(file, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
  } catch (err) {
    throw new Error(`${name} failed:\n${err.stdout ?? ''}${err.stderr ?? err.message}`);
  }
}

const apksigner = (...args) => run(java('java'), ['-jar', path.join(buildTools(), 'lib', 'apksigner.jar'), ...args], `apksigner ${args[0]}`);

function requirePassword() {
  if (!process.env[PASSWORD]) throw new Error(`${PASSWORD} must hold the keystore's password`);
}

export function normalizeSha256(hex) {
  const clean = String(hex ?? '').replace(/:/g, '').toLowerCase();
  if (!/^[0-9a-f]{64}$/.test(clean)) throw new Error(`"${hex}" is not a SHA-256 fingerprint`);
  return clean;
}

export function disposableKey(keystore) {
  requirePassword();
  if (fs.existsSync(keystore)) throw new Error(`${keystore} already exists`);
  run(java('keytool'), ['-genkeypair', '-keystore', keystore, '-storetype', 'PKCS12', '-alias', ALIAS,
    '-keyalg', 'RSA', '-keysize', '2048', '-validity', '1', '-dname', 'CN=Disposable test key',
    '-storepass:env', PASSWORD]);
}

// The SHA-256 of the key's certificate, as apksigner prints it for a signer.
export function keystoreCertSha256(keystore) {
  requirePassword();
  const der = execFileSync(java('keytool'), ['-exportcert', '-keystore', keystore, '-storetype', 'PKCS12',
    '-alias', ALIAS, '-storepass:env', PASSWORD], { stdio: ['ignore', 'pipe', 'pipe'] });
  return crypto.createHash('sha256').update(der).digest('hex');
}

export function sign(unsigned, signed, { keystore, version, certSha256 }) {
  versionCodeOf(version);
  const actual = keystoreCertSha256(keystore);
  if (certSha256 !== undefined && actual !== normalizeSha256(certSha256)) {
    throw new Error(`The keystore's certificate is ${actual}, not the expected ${normalizeSha256(certSha256)}. Nothing was signed.`);
  }
  if (fs.existsSync(signed)) throw new Error(`${signed} already exists`);
  apksigner('sign', '--ks', keystore, '--ks-type', 'PKCS12', '--ks-key-alias', ALIAS,
    '--ks-pass', `env:${PASSWORD}`, '--key-pass', `env:${PASSWORD}`,
    '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true',
    '--v4-signing-enabled', 'false', '--out', signed, unsigned);
  try {
    verify(signed, { version, certSha256: actual });
  } catch (err) {
    fs.rmSync(signed, { force: true });
    throw err;
  }
}

// Every problem with a signed APK, or an empty list.
export function problemsWith(apk, { version, certSha256 }) {
  const expectedCode = versionCodeOf(version);
  const expectedCert = normalizeSha256(certSha256);
  let signing;
  try {
    // From API 28, v3 is enough, so apksigner checks v2 only for an older minimum: 24 makes it check both.
    signing = apksigner('verify', '--verbose', '--print-certs', '--min-sdk-version', '24', apk);
  } catch (err) {
    return [`apksigner does not verify it: ${err.message.trim()}`];
  }
  const problems = [];
  const line = (label) => new RegExp(`^${label}: (.*)$`, 'm').exec(signing)?.[1].trim();
  if (line('Verified using v2 scheme \\(APK Signature Scheme v2\\)') !== 'true') problems.push('it has no v2 signature');
  if (line('Verified using v3 scheme \\(APK Signature Scheme v3\\)') !== 'true') problems.push('it has no v3 signature');
  if (line('Number of signers') !== '1') problems.push(`it has ${line('Number of signers') ?? 'no'} signers, not 1`);
  const certs = new Set([...signing.matchAll(/certificate SHA-256 digest: ([0-9a-f]{64})$/gm)].map((m) => m[1]));
  if (certs.size !== 1 || !certs.has(expectedCert)) {
    problems.push(`it is signed by certificate ${[...certs].join(' and ') || 'none'}, not ${expectedCert}`);
  }

  const badging = run(path.join(buildTools(), 'aapt2' + exe), ['dump', 'badging', apk]);
  const pkg = /^package: name='([^']*)' versionCode='([^']*)' versionName='([^']*)'/m.exec(badging);
  if (!pkg) return [...problems, 'aapt2 does not show its package'];
  if (pkg[1] !== PACKAGE) problems.push(`its package is ${pkg[1]}, not ${PACKAGE}`);
  if (pkg[3] !== version) problems.push(`its versionName is ${pkg[3]}, not ${version}`);
  if (pkg[2] !== String(expectedCode)) problems.push(`its versionCode is ${pkg[2]}, not ${expectedCode}`);
  if (/^application-debuggable$/m.test(badging)) problems.push('it is debuggable');
  return problems;
}

export function verify(apk, expected) {
  const problems = problemsWith(apk, expected);
  if (problems.length > 0) throw new Error(`${apk} fails its checks:\n- ${problems.join('\n- ')}`);
}

function options(args) {
  const named = {};
  const positional = [];
  for (let i = 0; i < args.length; i += 1) {
    if (args[i].startsWith('--')) named[args[i].slice(2)] = args[(i += 1)];
    else positional.push(args[i]);
  }
  return { named, positional };
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [command, ...rest] = process.argv.slice(2);
  const { named, positional } = options(rest);
  try {
    if (command === 'disposable-key' && positional.length === 1) {
      disposableKey(positional[0]);
    } else if (command === 'sign' && positional.length === 2 && named.keystore && named.version) {
      sign(positional[0], positional[1], { keystore: named.keystore, version: named.version, certSha256: named['cert-sha256'] });
      console.log(`Signed ${positional[1]} with v2 and v3 signatures; it passes its checks.`);
    } else if (command === 'verify' && positional.length === 1 && named.version && named['cert-sha256']) {
      verify(positional[0], { version: named.version, certSha256: named['cert-sha256'] });
      console.log(`${positional[0]} passes its checks.`);
    } else {
      throw new Error('Usage: see the comment at the top of tools/release/apk.mjs');
    }
  } catch (err) {
    console.error(err.message);
    process.exitCode = 1;
  }
}
