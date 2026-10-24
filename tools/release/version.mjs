// The app's versionCode for a release version, as app/build.gradle.kts computes it:
// major * 1,000,000 + minor * 1,000 + patch. Android's largest versionCode is 2,100,000,000.
export const DEVELOPMENT = '0.0.0-development';

export function versionCodeOf(version) {
  if (version === DEVELOPMENT) return 1;
  const match = /^(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})$/.exec(version);
  const [major, minor, patch] = match ? match.slice(1).map(Number) : [];
  if (!match || major > 2099 || major + minor + patch === 0) {
    throw new Error(`The app's version must be major.minor.patch between 0.0.1 and 2099.999.999, such as 1.2.3; "${version}" is not.`);
  }
  return major * 1_000_000 + minor * 1_000 + patch;
}
