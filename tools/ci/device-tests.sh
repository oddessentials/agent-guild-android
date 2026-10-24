#!/usr/bin/env bash
# Runs the instrumented tests on the one emulator adb sees, once Android is ready for them. If the
# check or the tests fail, it saves what shows why into <diagnostics dir> and exits non-zero.
#   tools/ci/device-tests.sh <diagnostics dir> [Gradle arguments]
# MANAGER_TOKEN, when set, is the scratch manager's token. It goes to the tests as the managerToken
# argument, and any saved file that contains it is deleted. MANAGER_LOG, when set, is the manager's
# log, saved with every token in it replaced.
set -uo pipefail

out=$1
shift
mkdir -p "$out"

diagnose() {
  echo "$1" > "$out/failure.txt"
  {
    adb shell getprop ro.build.fingerprint
    adb shell dumpsys webviewupdate
  } > "$out/device.txt" 2>&1
  # adbd logs the am instrument command that starts the tests, test arguments included.
  if [ -n "${MANAGER_TOKEN:-}" ]; then
    adb logcat -d -v threadtime 2>&1 | sed "s/$MANAGER_TOKEN/<token>/g" > "$out/logcat.txt"
  else
    adb logcat -d -v threadtime > "$out/logcat.txt" 2>&1
  fi
  adb shell dumpsys activity activities > "$out/activities.txt" 2>&1
  adb exec-out screencap -p > "$out/screen.png" 2>/dev/null
  for reports in app/build/reports/androidTests app/build/outputs/androidTest-results; do
    if [ -d "$reports" ]; then cp -r "$reports" "$out/"; fi
  done
  if [ -n "${MANAGER_LOG:-}" ] && [ -f "$MANAGER_LOG" ]; then
    sed -E 's/[0-9a-f]{32,}/<token>/g' "$MANAGER_LOG" > "$out/manager.log"
  fi
  if [ -n "${MANAGER_TOKEN:-}" ]; then
    grep -rlF -- "$MANAGER_TOKEN" "$out" | while read -r leaked; do
      rm -f -- "$leaked"
      echo "${leaked#"$out"/} contained the manager's token and was deleted." >> "$out/deleted.txt"
    done
  fi
  echo "::error::$1 The diagnostics are in the job's artifacts."
}

# The emulator action waits for sys.boot_completed. The tests also need the package manager answering
# and a WebView provider chosen; both are checked until a deadline.
ready() {
  adb shell pm path android > /dev/null 2>&1 &&
    adb shell dumpsys webviewupdate 2> /dev/null | grep -q 'Current WebView package (name, version): ('
}
deadline=$((SECONDS + 120))
until ready; do
  if [ "$SECONDS" -ge "$deadline" ]; then
    diagnose 'Android booted, but was not ready for the tests within 2 minutes.'
    exit 1
  fi
  sleep 1
done
adb shell dumpsys webviewupdate | grep 'Current WebView package'

arguments=("$@")
if [ -n "${MANAGER_TOKEN:-}" ]; then
  arguments+=("-Pandroid.testInstrumentationRunnerArguments.managerToken=$MANAGER_TOKEN")
fi
if ! ./gradlew :app:connectedDebugAndroidTest "${arguments[@]}"; then
  diagnose 'The instrumented tests failed.'
  exit 1
fi
# A test filter that matches nothing passes with no tests run.
ran=$(cat app/build/outputs/androidTest-results/connected/debug/TEST-*.xml 2> /dev/null | grep -c '<testcase ')
if [ "$ran" -eq 0 ]; then
  diagnose 'No instrumented test ran.'
  exit 1
fi
echo "$ran instrumented tests passed."
