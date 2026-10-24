import fs from 'node:fs';
import path from 'node:path';
import { versionCodeOf } from '../../tools/release/version.mjs';

export async function generateNotes(pluginConfig, { env, lastRelease, nextRelease }) {
  const dir = env.RELEASE_PLAN_DIR;
  if (!dir) throw new Error('RELEASE_PLAN_DIR must name the folder that receives the release plan');
  if (!lastRelease?.version) {
    throw new Error('No release tag was found, so the next version cannot follow from one. Tag the commit before the first release, for example v0.0.0.');
  }
  // The app's versionCode comes from the version, so a version without one is refused here, before anything is built.
  versionCodeOf(nextRelease.version);
  const install = [
    '### Install',
    '',
    `Download \`agent-guild-${nextRelease.version}.apk\` below and open it on the Android device. It installs over an earlier release.`,
  ].join('\n');
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, 'version'), nextRelease.version);
  fs.writeFileSync(path.join(dir, 'notes.md'), `${[nextRelease.notes, install].filter(Boolean).join('\n\n')}\n`);
  return install;
}
