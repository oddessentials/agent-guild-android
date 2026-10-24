// Checks tools/release/publish.mjs against a GitHub kept in memory: a first release, re-runs after
// each interruption, and every mismatch it must refuse without changing anything.
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { publish } from '../../tools/release/publish.mjs';

const repo = 'oddessentials/agent-guild-android';
const tag = 'v0.1.0';
const sha = 'a'.repeat(40);
const apk = 'agent-guild-0.1.0.apk';

// The parts of gh that publish.mjs uses, over { tags, releases }. `interrupt` names an operation
// that fails once; `corrupt` names an asset that downloads with a changed byte.
function fakeGitHub(setup = {}) {
  const state = { tags: new Map(), releases: new Map(), writes: [], interrupt: null, corrupt: null, ...setup };
  const interrupted = (op) => {
    if (state.interrupt !== op) return;
    state.interrupt = null;
    throw new Error(`gh: ${op} was interrupted`);
  };
  const flag = (args, name) => args[args.indexOf(name) + 1];
  const files = (args, from) => args.slice(from).filter((a, i, all) => !a.startsWith('--') && !all[i - 1]?.startsWith('--'));
  const gh = (args) => {
    const [a, b] = args;
    if (a === 'api' && b === `repos/${repo}/git/refs`) {
      interrupted('tag');
      const ref = args.find((x) => x.startsWith('ref=')).slice('ref=refs/tags/'.length);
      if (state.tags.has(ref)) throw new Error('gh: Reference already exists (HTTP 422)');
      state.writes.push(`tag ${ref}`);
      state.tags.set(ref, args.find((x) => x.startsWith('sha=')).slice(4));
      return '';
    }
    if (a === 'api' && b.startsWith(`repos/${repo}/git/ref/tags/`)) {
      const sha = state.tags.get(b.split('/').pop());
      if (!sha) throw new Error('gh: Not Found (HTTP 404)');
      return `${sha}\n`;
    }
    if (a === 'release' && b === 'list') {
      return JSON.stringify([...state.releases].map(([tagName, r]) => ({ tagName, isDraft: r.isDraft })));
    }
    const release = state.releases.get(args[2]);
    if (a === 'release' && b === 'view') {
      if (!release) throw new Error('release not found');
      return JSON.stringify({ tagName: args[2], isDraft: release.isDraft, targetCommitish: release.target,
        assets: [...release.assets.keys()].map((name) => ({ name })) });
    }
    if (a === 'release' && b === 'create') {
      if (release) throw new Error('gh: a release with the same tag name already exists');
      const created = { isDraft: args.includes('--draft'), target: flag(args, '--target'), assets: new Map() };
      state.releases.set(args[2], created);
      state.writes.push(`create ${args[2]}`);
      for (const [i, file] of files(args, 3).entries()) {
        if (i > 0) interrupted('upload');
        created.assets.set(path.basename(file), fs.readFileSync(file));
      }
      return '';
    }
    if (a === 'release' && b === 'upload') {
      for (const file of files(args, 3)) {
        if (release.assets.has(path.basename(file))) throw new Error(`asset ${path.basename(file)} already exists`);
        state.writes.push(`upload ${path.basename(file)}`);
        release.assets.set(path.basename(file), fs.readFileSync(file));
      }
      return '';
    }
    if (a === 'release' && b === 'download') {
      for (const [name, bytes] of release.assets) {
        const copy = Buffer.from(bytes);
        if (name === state.corrupt) copy[0] ^= 1;
        fs.writeFileSync(path.join(flag(args, '--dir'), name), copy);
      }
      return '';
    }
    if (a === 'release' && b === 'edit') {
      interrupted('publish');
      if (args.includes('--verify-tag') && !state.tags.has(args[2])) throw new Error('gh: tag does not exist');
      state.writes.push(`publish ${args[2]}`);
      release.isDraft = false;
      return '';
    }
    throw new Error(`the fake gh does not know: gh ${args.join(' ')}`);
  };
  return { gh, state };
}

function localRelease(bytes = 'signed apk') {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'agent-guild-publish-'));
  fs.writeFileSync(path.join(dir, apk), bytes);
  const hash = (b) => crypto.createHash('sha256').update(b).digest('hex');
  fs.writeFileSync(path.join(dir, 'SHA256SUMS'), `${hash(bytes)}  ${apk}\n`);
  const notes = path.join(dir, '..', `${path.basename(dir)}-notes.md`);
  fs.writeFileSync(notes, 'notes');
  return { dir, notes, bytes: (name) => fs.readFileSync(path.join(dir, name)), cleanup() {
    fs.rmSync(dir, { recursive: true });
    fs.rmSync(notes);
  } };
}

function run(github, local, options = {}) {
  try {
    return { outcome: publish({ gh: github.gh, repo, tag, sha, notes: local.notes, dir: local.dir, log: () => {}, ...options }) };
  } catch (err) {
    return { error: err.message };
  }
}

// The state a complete release leaves: public, tagged on this commit, holding exactly these files.
function isPublic(github, local) {
  const r = github.state.releases.get(tag);
  return r && !r.isDraft && github.state.tags.get(tag) === sha && r.assets.size === 2 &&
    r.assets.get(apk).equals(local.bytes(apk)) && r.assets.get('SHA256SUMS').equals(local.bytes('SHA256SUMS'));
}

const cases = {
  'a first run creates a draft, checks it, tags and publishes'(github, local) {
    const { outcome, error } = run(github, local);
    return [outcome === 'published' || `got ${error ?? outcome}`, isPublic(github, local) || 'not public as expected',
      github.state.writes.join(',') === `create ${tag},tag ${tag},publish ${tag}` || `wrote ${github.state.writes}`];
  },
  'a re-run after success changes nothing'(github, local) {
    run(github, local);
    const before = github.state.writes.length;
    const { outcome, error } = run(github, local);
    return [outcome === 'already public' || `got ${error ?? outcome}`, github.state.writes.length === before || 'it wrote again'];
  },
  'a run interrupted while uploading is finished by a re-run, which adds only what is missing'(github, local) {
    github.state.interrupt = 'upload';
    const first = run(github, local);
    const draftAfterFirst = github.state.releases.get(tag)?.isDraft;
    const { outcome, error } = run(github, local);
    return [first.error !== undefined || 'the first run did not fail', draftAfterFirst === true || 'the failed run did not leave a draft',
      outcome === 'published' || `got ${error ?? outcome}`, isPublic(github, local) || 'not public as expected',
      github.state.writes.filter((w) => w.startsWith('create')).length === 1 || 'it created the release twice',
      !github.state.writes.includes(`upload ${apk}`) || 'it uploaded the APK again'];
  },
  'a run interrupted before tagging stays a draft, and a re-run finishes it'(github, local) {
    github.state.interrupt = 'tag';
    const first = run(github, local);
    const draft = github.state.releases.get(tag)?.isDraft === true && !github.state.tags.has(tag);
    const { outcome, error } = run(github, local);
    return [first.error !== undefined || 'the first run did not fail', draft || 'the failed run did not leave an untagged draft',
      outcome === 'published' || `got ${error ?? outcome}`, isPublic(github, local) || 'not public as expected'];
  },
  'a run interrupted while publishing stays a draft, and a re-run finishes it'(github, local) {
    github.state.interrupt = 'publish';
    const first = run(github, local);
    const draft = github.state.releases.get(tag)?.isDraft === true && github.state.tags.get(tag) === sha;
    const { outcome, error } = run(github, local);
    return [first.error !== undefined || 'the first run did not fail', draft || 'the failed run did not leave a tagged draft',
      outcome === 'published' || `got ${error ?? outcome}`, isPublic(github, local) || 'not public as expected'];
  },
  'an asset that downloads differently stops it before the tag, and the release stays a draft'(github, local) {
    github.state.corrupt = apk;
    const { error } = run(github, local);
    return [/holds a different agent-guild-0\.1\.0\.apk/.test(error) || `got ${error}`,
      github.state.releases.get(tag)?.isDraft === true || 'it is not a draft', !github.state.tags.has(tag) || 'it was tagged'];
  },
  'a draft holding a different APK is refused and not replaced'(github, local) {
    github.state.releases.set(tag, { isDraft: true, target: sha, assets: new Map([[apk, Buffer.from('another apk')]]) });
    const { error } = run(github, local);
    const held = github.state.releases.get(tag).assets.get(apk).toString();
    return [/holds a different agent-guild-0\.1\.0\.apk/.test(error) || `got ${error}`, held === 'another apk' || 'the APK was replaced',
      github.state.writes.every((w) => !w.startsWith('tag') && !w.startsWith('publish')) || `wrote ${github.state.writes}`];
  },
  'a draft holding an asset this release lacks is refused'(github, local) {
    github.state.releases.set(tag, { isDraft: true, target: sha, assets: new Map([['extra.txt', Buffer.from('x')]]) });
    const { error } = run(github, local);
    return [/holds extra\.txt/.test(error) || `got ${error}`, !github.state.tags.has(tag) || 'it was tagged'];
  },
  'a draft for another commit is refused'(github, local) {
    github.state.releases.set(tag, { isDraft: true, target: 'b'.repeat(40), assets: new Map() });
    const { error } = run(github, local);
    return [/is for b{40}, not a{40}/.test(error) || `got ${error}`, github.state.writes.length === 0 || `wrote ${github.state.writes}`];
  },
  'a tag on another commit stops it before anything is written'(github, local) {
    github.state.tags.set(tag, 'b'.repeat(40));
    const { error } = run(github, local);
    return [/already points at b{40}/.test(error) || `got ${error}`, github.state.writes.length === 0 || `wrote ${github.state.writes}`];
  },
  'a public release holding a different APK is refused and left alone'(github, local) {
    github.state.tags.set(tag, sha);
    github.state.releases.set(tag, { isDraft: false, target: sha, assets: new Map([[apk, Buffer.from('another apk')], ['SHA256SUMS', local.bytes('SHA256SUMS')]]) });
    const { error } = run(github, local);
    return [/public release v0\.1\.0 holds a different/.test(error) || `got ${error}`, github.state.writes.length === 0 || `wrote ${github.state.writes}`];
  },
  'another draft release stops it before anything is written'(github, local) {
    github.state.releases.set('v0.0.9', { isDraft: true, target: sha, assets: new Map() });
    const { error } = run(github, local);
    return [/v0\.0\.9 is still a draft/.test(error) || `got ${error}`, github.state.writes.length === 0 || `wrote ${github.state.writes}`];
  },
  'files that do not match SHA256SUMS stop it before it asks GitHub anything'(github, local) {
    fs.writeFileSync(path.join(local.dir, apk), 'changed after signing');
    let asked = false;
    const { error } = run({ gh: () => { asked = true; return ''; }, state: github.state }, local);
    return [/does not match its SHA-256/.test(error) || `got ${error}`, !asked || 'it asked GitHub'];
  },
};

let failed = 0;
for (const [name, test] of Object.entries(cases)) {
  const local = localRelease();
  let problems;
  try {
    problems = test(fakeGitHub(), local).filter((r) => r !== true);
  } catch (err) {
    problems = [err.stack];
  } finally {
    local.cleanup();
  }
  if (problems.length > 0) failed += 1;
  console.log(`${problems.length > 0 ? 'not ok' : 'ok'}  ${name}`);
  for (const problem of problems) console.log(`        ${problem}`);
}
process.exitCode = failed > 0 ? 1 : 0;
