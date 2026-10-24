// Puts a signed release on GitHub, or finishes one an interrupted run started.
//   node tools/release/publish.mjs --repo <owner/name> --tag v<version> --sha <commit> --notes <notes.md> <dir>
// <dir> holds SHA256SUMS and the files it lists; those files and SHA256SUMS are the release's assets.
//
// The release stays a draft until the assets it holds are downloaded and match these files byte for
// byte; only then is the tag created and the release made public. Running it again reuses whatever
// already matches: a tag on this commit, a draft for this commit, assets with these contents. Anything
// that doesn't match stops it, and nothing is replaced. Uses the gh CLI, which reads GH_TOKEN.
import { execFileSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const SUMS = 'SHA256SUMS';

const hashOf = (file) => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');

// The assets by name, each with its SHA-256, after checking that SHA256SUMS matches the files.
export function assetsIn(dir) {
  const sums = path.join(dir, SUMS);
  if (!fs.existsSync(sums)) throw new Error(`${dir} has no ${SUMS}`);
  const assets = new Map();
  for (const line of fs.readFileSync(sums, 'utf8').split('\n').filter(Boolean)) {
    const match = /^([0-9a-f]{64}) [ *]([^/\\]+)$/.exec(line);
    if (!match || match[2] === SUMS || assets.has(match[2])) throw new Error(`${SUMS} has a line it should not: "${line}"`);
    const file = path.join(dir, match[2]);
    if (!fs.existsSync(file) || hashOf(file) !== match[1]) throw new Error(`${match[2]} does not match its SHA-256 in ${SUMS}`);
    assets.set(match[2], match[1]);
  }
  if (assets.size === 0) throw new Error(`${SUMS} lists no files`);
  assets.set(SUMS, hashOf(sums));
  return assets;
}

export function publish({ gh, repo, tag, sha, notes, dir, log = console.log }) {
  const assets = assetsIn(dir);
  const notFound = (err, pattern) => {
    if (pattern.test(err.message)) return null;
    throw err;
  };
  const tagged = () => {
    try {
      return gh(['api', `repos/${repo}/git/ref/tags/${tag}`, '--jq', '.object.sha']).trim();
    } catch (err) {
      return notFound(err, /HTTP 404/);
    }
  };
  const view = () => {
    try {
      return JSON.parse(gh(['release', 'view', tag, '--repo', repo, '--json', 'tagName,isDraft,targetCommitish,assets']));
    } catch (err) {
      return notFound(err, /release not found/);
    }
  };
  // Checks every asset the release holds against these files.
  const checkHeld = (state) => {
    const held = fs.mkdtempSync(path.join(os.tmpdir(), 'agent-guild-release-'));
    try {
      gh(['release', 'download', tag, '--repo', repo, '--dir', held]);
      const names = fs.readdirSync(held);
      for (const name of names) {
        if (!assets.has(name)) throw new Error(`The ${state} release ${tag} holds ${name}, which this release does not have.`);
      }
      for (const [name, hash] of assets) {
        if (!names.includes(name)) throw new Error(`The ${state} release ${tag} does not hold ${name}.`);
        if (hashOf(path.join(held, name)) !== hash) {
          throw new Error(`The ${state} release ${tag} holds a different ${name} from the one this run signed and checked.`);
        }
      }
    } finally {
      fs.rmSync(held, { recursive: true, force: true });
    }
  };

  const tagSha = tagged();
  if (tagSha !== null && tagSha !== sha) throw new Error(`${tag} already points at ${tagSha}, not at ${sha}.`);
  const release = view();
  if (release && !release.isDraft) {
    if (tagSha !== sha) throw new Error(`${tag} is public, but its tag does not point at ${sha}.`);
    checkHeld('public');
    log(`${tag} is already public, on ${sha}, with these assets.`);
    return 'already public';
  }

  const drafts = JSON.parse(gh(['release', 'list', '--repo', repo, '--json', 'tagName,isDraft', '--limit', '1000']))
    .filter((r) => r.isDraft && r.tagName !== tag);
  if (drafts.length > 0) {
    throw new Error(`Release ${drafts.map((r) => r.tagName).join(', ')} is still a draft. Finish it by re-running its failed release job, or delete it, before releasing ${tag}.`);
  }

  if (!release) {
    log(`Creating the draft release ${tag}.`);
    gh(['release', 'create', tag, '--repo', repo, '--draft', '--target', sha, '--title', tag, '--notes-file', notes,
      ...[...assets.keys()].map((name) => path.join(dir, name))]);
  } else {
    if (release.targetCommitish !== sha) {
      throw new Error(`The draft release ${tag} is for ${release.targetCommitish}, not ${sha}. Delete the draft to release ${tag} from this commit.`);
    }
    const held = new Set(release.assets.map((a) => a.name));
    const missing = [...assets.keys()].filter((name) => !held.has(name));
    if (missing.length > 0) {
      log(`Adding ${missing.join(', ')} to the draft release ${tag}.`);
      gh(['release', 'upload', tag, '--repo', repo, ...missing.map((name) => path.join(dir, name))]);
    }
  }
  checkHeld('draft');

  if (tagSha === null) {
    log(`Tagging ${sha} as ${tag}.`);
    gh(['api', `repos/${repo}/git/refs`, '--silent', '-f', `ref=refs/tags/${tag}`, '-f', `sha=${sha}`]);
  }
  gh(['release', 'edit', tag, '--repo', repo, '--draft=false', '--verify-tag']);
  if (view()?.isDraft !== false) throw new Error(`${tag} is still a draft after publishing it.`);
  log(`${tag} is public.`);
  return 'published';
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const args = process.argv.slice(2);
  const named = {};
  const positional = [];
  for (let i = 0; i < args.length; i += 1) {
    if (args[i].startsWith('--')) named[args[i].slice(2)] = args[(i += 1)];
    else positional.push(args[i]);
  }
  const gh = (ghArgs) => {
    try {
      return execFileSync('gh', ghArgs, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
    } catch (err) {
      throw new Error(`gh ${ghArgs.slice(0, 2).join(' ')} failed: ${err.stderr || err.message}`);
    }
  };
  try {
    if (!named.repo || !/^v\d+\.\d+\.\d+$/.test(named.tag ?? '') || !/^[0-9a-f]{40}$/.test(named.sha ?? '') || !named.notes || positional.length !== 1) {
      throw new Error('Usage: see the comment at the top of tools/release/publish.mjs');
    }
    publish({ gh, repo: named.repo, tag: named.tag, sha: named.sha, notes: named.notes, dir: positional[0] });
  } catch (err) {
    console.error(`${process.env.GITHUB_ACTIONS ? '::error::' : ''}${err.message}`);
    process.exitCode = 1;
  }
}
