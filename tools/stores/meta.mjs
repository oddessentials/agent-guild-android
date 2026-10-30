import { execFile } from 'node:child_process';
import { main, redact, StoreError } from './report.mjs';

export const NAMES = ['META_APP_ID', 'META_APP_SECRET'];

function runCli(file, args) {
  return new Promise((resolve) => {
    execFile(file, args, { maxBuffer: 64 * 1024 * 1024 }, (err, stdout, stderr) => {
      resolve({ code: err ? (err.code ?? 1) : 0, output: `${stdout}${stderr}` });
    });
  });
}

export async function publish({ env, apk, version, notes = '', run = runCli }) {
  const appId = env.META_APP_ID;
  if (!/^[0-9]+$/.test(appId)) throw new StoreError('META_APP_ID must be the numeric app ID.');
  if (!env.META_CLI) throw new StoreError('META_CLI must name the ovr-platform-util binary.');
  const { code, output } = await run(env.META_CLI, [
    'upload-quest-build',
    '--app-id', appId,
    '--app-secret', env.META_APP_SECRET,
    '--apk', apk,
    '--channel', 'store',
    '--age-group', 'TEENS_AND_ADULTS',
    '--draft',
    '--wait-for-postprocessing',
    '--disable-progress-bar',
    // The utility reads \n in its notes as a line break.
    ...(notes ? ['--notes', notes.replace(/\n/g, '\\n')] : []),
  ]);
  const build = /Created Build ID: ([0-9]+)/.exec(output)?.[1];
  if (code !== 0 || !build) {
    const tail = redact(output, [env.META_APP_SECRET]).trim().split('\n').slice(-15).join('\n');
    throw new StoreError(`The upload did not create a build (exit code ${code}):\n${tail}`);
  }
  return {
    status: 'manual action required',
    message: `Uploaded ${version} as draft build ${build} on the Production (Store) channel. It is not submitted: submit it for review at https://developers.meta.com/horizon/manage/applications/${appId}/`,
  };
}

await main(import.meta, { name: 'Meta Horizon Store', names: NAMES, publish });
