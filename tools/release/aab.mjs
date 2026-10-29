import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { keystoreCertSha256, normalizeSha256, PACKAGE } from './apk.mjs';
import { versionCodeOf } from './version.mjs';

const ALIAS = 'agent-guild';
const PASSWORD = 'RELEASE_KEYSTORE_PASSWORD';
const exe = process.platform === 'win32' ? '.exe' : '';

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

function bundletool(...args) {
  const jar = process.env.BUNDLETOOL_JAR;
  if (!jar || !fs.existsSync(jar)) throw new Error('BUNDLETOOL_JAR must name bundletool-all-1.18.3.jar');
  return run(java('java'), ['-jar', jar, ...args], `bundletool ${args.slice(0, 2).join(' ')}`);
}

export function sign(unsigned, signed, { keystore, version, certSha256 }) {
  versionCodeOf(version);
  const actual = keystoreCertSha256(keystore);
  if (certSha256 !== undefined && actual !== normalizeSha256(certSha256)) {
    throw new Error(`The keystore's certificate is ${actual}, not the expected ${normalizeSha256(certSha256)}. Nothing was signed.`);
  }
  if (fs.existsSync(signed)) throw new Error(`${signed} already exists`);
  run(java('jarsigner'), ['-keystore', keystore, '-storetype', 'PKCS12', '-storepass:env', PASSWORD, '-keypass:env', PASSWORD,
    '-digestalg', 'SHA-256', '-sigalg', 'SHA256withRSA', '-signedjar', signed, unsigned, ALIAS]);
  try {
    verify(signed, { version, certSha256: actual });
  } catch (err) {
    fs.rmSync(signed, { force: true });
    throw err;
  }
}

export function problemsWith(aab, { version, certSha256 }) {
  const expectedCode = versionCodeOf(version);
  const expectedCert = normalizeSha256(certSha256);
  let checked;
  try {
    checked = run(java('jarsigner'), ['-verify', aab], 'jarsigner -verify');
  } catch (err) {
    return [`jarsigner does not verify it: ${err.message.trim()}`];
  }
  if (!/^jar verified\./m.test(checked) || /unsigned entries|jar is unsigned/.test(checked)) {
    return [`jarsigner does not verify it: ${checked.trim()}`];
  }
  const problems = [];
  const printed = run(java('keytool'), ['-printcert', '-jarfile', aab], 'keytool -printcert');
  const signers = printed.match(/^Signer #\d+:/gm) ?? [];
  if (signers.length !== 1) problems.push(`it has ${signers.length || 'no'} signers, not 1`);
  const certs = new Set([...printed.matchAll(/^\s*SHA256: ([0-9A-F:]{95})$/gm)].map((m) => normalizeSha256(m[1])));
  if (certs.size !== 1 || !certs.has(expectedCert)) {
    problems.push(`it is signed by certificate ${[...certs].join(' and ') || 'none'}, not ${expectedCert}`);
  }

  const manifest = bundletool('dump', 'manifest', '--bundle', aab);
  const attr = (name) => new RegExp(`\\s${name}="([^"]*)"`).exec(manifest)?.[1];
  const pkg = /<manifest\b[^>]*\spackage="([^"]*)"/.exec(manifest)?.[1];
  if (pkg !== PACKAGE) problems.push(`its package is ${pkg ?? 'missing'}, not ${PACKAGE}`);
  if (attr('android:versionName') !== version) problems.push(`its versionName is ${attr('android:versionName')}, not ${version}`);
  if (attr('android:versionCode') !== String(expectedCode)) problems.push(`its versionCode is ${attr('android:versionCode')}, not ${expectedCode}`);
  if (attr('android:debuggable') === 'true') problems.push('it is debuggable');
  return problems;
}

export function verify(aab, expected) {
  const problems = problemsWith(aab, expected);
  if (problems.length > 0) throw new Error(`${aab} fails its checks:\n- ${problems.join('\n- ')}`);
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
    if (command === 'sign' && positional.length === 2 && named.keystore && named.version) {
      sign(positional[0], positional[1], { keystore: named.keystore, version: named.version, certSha256: named['cert-sha256'] });
      console.log(`Signed ${positional[1]}; it passes its checks.`);
    } else if (command === 'verify' && positional.length === 1 && named.version && named['cert-sha256']) {
      verify(positional[0], { version: named.version, certSha256: named['cert-sha256'] });
      console.log(`${positional[0]} passes its checks.`);
    } else {
      throw new Error('Usage: node tools/release/aab.mjs sign <unsigned.aab> <signed.aab> --keystore <keystore> --version <version> [--cert-sha256 <hex>] | verify <signed.aab> --version <version> --cert-sha256 <hex>');
    }
  } catch (err) {
    console.error(err.message);
    process.exitCode = 1;
  }
}
