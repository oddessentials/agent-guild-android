# Agent Guild for Android

An app that connects to a computer running [Agent Guild](https://github.com/oddessentials/agent-guild) and shows its Cards and Yard, as [agent-guild-apple](https://github.com/oddessentials/agent-guild-apple) does on iPhone, iPad and Apple Vision Pro.

This repository holds the app, its tests, the emulator tools, a verification probe, and the CI and release workflows. See [The app](#the-app) for how it is built and tested.

## The approach

The app shows the manager's own page in a `WebView`, loaded from the manager's remote-access address with `#token=`. It adds what the page cannot do itself: keeping a list of computers, signing in, and opening links outside.

- **Never copy the page into the app.** The manager answers 403 to `Origin: null` and to custom schemes (`checkRequestSource` in agent-guild's `src/manager/server.mjs`), and a copy would drift from the manager it talks to. Loading the page from the manager keeps the app in step with every manager version.
- **Every way of running Agent Guild runs the same Node manager**, so there is one kind of server to connect to.
- **No Google Play services.** The app is sideloaded, including onto devices without them.

## How the app reaches a computer

- The manager listens on `127.0.0.1` only and refuses a `Host` outside its allowlist. An address is a Tailscale Serve route (`https://<computer>.<tailnet>.ts.net[:port]`, set up in Agent Guild's **Settings → Remote access**) or a reverse proxy saved there. Tailscale Serve's certificate is a public one for the `ts.net` name.
- **Remote access → Connect another device** shows the sign-in link as a QR code and as text.
- `GET /api/v1/health` needs no token. Every other request carries `Authorization: Bearer <token>`. See agent-guild's `docs/api.md`.

### The sign-in link

The manager's page builds the link in `web/remote-access.js`: the remote-access address with the fragment set by `new URLSearchParams({ token })`, so `https://<host>[:<port>]/#token=<token>`. The manager creates the token as 24 random bytes in lowercase hex, and accepts a saved token that matches `^[a-f0-9]{32,}$` (`src/manager/config.mjs`).

The app's parser accepts a link only when every rule holds, and otherwise says it isn't an Agent Guild sign-in link:

1. Leading and trailing whitespace is ignored. The rest parses as an absolute URL.
2. The scheme is `https`. Debug builds also accept `http://127.0.0.1:<port>`, for the scratch manager below; release builds never do.
3. There is a host, and no user name or password.
4. The path is empty or `/`, and there is no query.
5. The fragment is exactly one `token=<value>` pair, and the value matches `^[a-f0-9]{32,}$`. Anything else in the fragment, a second `token`, or percent-encoding in the value makes the link ambiguous, and it is refused.

The saved address is `https://<host>[:<port>]` with the host in lowercase, and no `:443`. Before saving, the app checks `GET <address>/api/v1/info` with the bearer token, a 10-second timeout and no redirects, and accepts it only when the answer is 200 with `"name": "agent-guild"`.

The token never appears in a log line, a crash report, a Custom Tab, or any URL other than the one the `WebView` loads. The page removes it from its own address and history once loaded. Any URL the app logs has its fragment removed.

## The page in the app: the bridge contract

agent-guild's `docs/api.md`, **The page in the Agent Guild app → Android**, is the contract. In short:

- The app registers a web message listener named `agentGuild` with `WebViewCompat.addWebMessageListener`, whose allowed origin rules hold exactly the manager's origin.
- The page sees `window.agentGuild.postMessage` and treats the app as its host. It posts JSON text, `{"source":"agent-guild","type":...}`: `ready` with `version`, `open` with `url`, and `expand` in the compact layout.
- The app accepts a message only when `isMainFrame` is true and `sourceOrigin` equals the manager's origin, then parses the JSON and accepts only those types with their fields.
- To show a view, the app runs `window.postMessage({ source: "agent-guild-app", type: "view", view }, location.origin)` with `evaluateJavascript` after `onPageFinished`. `view` is `"cards"` or `"yard"`, from a fixed list, never from text.

The page needs agent-guild's change "feat: support Agent Guild in the Android app web view". The app's minimum Agent Guild version is the first release that carries it, 1.9.0 (`Health.MINIMUM_VERSION`). A development build reports `0.0.0-development` and is accepted, as the Apple app does.

The app accepts `ready` and `open`, and ignores `look`. On a TV it loads the compact layout with `&expand=none`, so the page sends no `expand`.

### Skin and theme on a TV

The compact page keeps no skin or theme of its own, so on a TV the app chooses them. **Skin and Theme**, in a computer's or the demo's ⋮ menu or from the remote's Menu key over the Yard, opens a panel beside it:

- The skins are the page's own `window.agentGuildSkins`, read once the page has loaded, so the app never copies the list. A skin counts only with an id matching `^[a-z]{1,40}$` and a name of at most 40 characters; the theme is System, Light or Dark.
- Each choice shows at once: the app sets `data-skin` and `data-theme` on the page, which the Yard redraws on, and loads nothing. A skin the page doesn't list is never set. System resolves as the page's `theme.js` does without `&theme=`.
- Save keeps the choice for that computer, or for the demo, and closes the panel only once the write succeeded; otherwise the saved one is unchanged and the panel says so.
- Opened from the list, Save or Cancel goes back to the list; opened with Menu, they stay on the Yard.
- Cancel, Back and Menu put back the `data-skin` and `data-theme` the page showed when the panel opened, including a skin the page doesn't list; a missing attribute stays missing.
- Every load the app starts adds the saved skin and theme to the compact URL: `&skin=<id>&theme=<light|dark>`, with no `&theme=` for System. Agent Guild 1.5.0 and later read both. A successful Save also writes them into the current page's address with `history.replaceState`, keeping its other parameters, so a reload the page starts itself shows the saved look.
- A new document closes the panel: the app's own load, one the page starts itself, or a new `WebView` after the renderer is gone. An answer from the page counts only for the document that asked.
- The Menu key is taken in `MainActivity.dispatchKeyEvent`, before the page sees it, and only while a TV page shows. A held key acts once.
- The list starts with the focus on a control, so the remote needs no press to get one: the row or ⋮ it was left from, else the first computer, else Add Computer.

## WebView security

No new navigation framework: the app uses `WebViewClient` and `WebChromeClient` directly.

- **First load:** only a URL built from a saved, validated computer: `<address>/#token=<token>` plus `&view=`, and on a TV the compact layout's `&layout=compact&expand=none` with the saved skin and theme.
- **Navigation and redirects:** `shouldOverrideUrlLoading` cancels any main-frame load whose origin isn't the manager's. A link the user followed opens in a Custom Tab instead, only for `http` and `https`. Server redirects reach the same check, and are never opened outside. As a second guard, `onPageStarted` stops a main-frame page whose origin isn't the manager's and shows the error screen; it is what stops a form posted elsewhere, which `shouldOverrideUrlLoading` never sees. A URL that contains the token never goes to a Custom Tab, from a link or from the bridge.
- **New windows:** `setSupportMultipleWindows(true)`, and `onCreateWindow` returns `false`. The page sends links through the bridge instead.
- **Renderer recovery:** `onRenderProcessGone` destroys the `WebView`, creates a new one and loads the same validated URL.
- **Settings:** JavaScript and DOM storage on; file and content access off; mixed content never allowed; no `addJavascriptInterface`; `setWebContentsDebuggingEnabled` in debug builds only.
- **Microphone:** `onPermissionRequest` grants only `PermissionRequest.RESOURCE_AUDIO_CAPTURE`, only when the request's origin is exactly the manager's, and only after the user has granted the `RECORD_AUDIO` runtime permission; the app asks for it at the page's first request. Every other request, origin or resource is denied.
- **Storage of computers:** name, address and token in one DataStore file, `files/datastore/computers.sealed`, encrypted with a 256-bit AES-GCM key from the Android Keystore. A file that can't be opened, such as one without its key, reads as an empty list. The page keeps its own copy of the token in the `WebView`'s local storage, so `dataExtractionRules` and `fullBackupContent` keep all of the app's data out of cloud backups and device transfers, and `allowBackup` is off. The link being typed into Add Computer isn't saved with the activity's state.
- **Storage of looks:** each TV page's skin and theme, keyed by computer id or `demo`, in `files/datastore/looks.json`. It holds nothing secret, so it isn't sealed; the same backup rules keep it on the device. Removing a computer removes its look.

## What was verified on the emulator

The probe in `tools/probe` checked each of these in the Android 17 emulator with WebView 145, against the real page served by a scratch manager:

| Question | Result |
| --- | --- |
| Is `window.agentGuild` there before the page's first script? | Yes. An inline script in `<head>` sees it, so `theme.js` does. |
| What does `postMessage` carry? | Strings only. An object throws `TypeError`. JSON text arrives with `isMainFrame` true and the page's origin. |
| Does the page work as the app's host? | Yes, with agent-guild's Android host change: `ready` arrives as JSON, `#view=yard` opens and keeps the Yard, the token leaves the address, and the `view` message switches views. |
| Dictate | The `WebView`'s `webkitSpeechRecognition` uses Android's own speech recognition service. It reaches `audiostart`, and the service returns its final result (empty, with nothing spoken). |
| Dictate without a speech service | `start`, then `aborted`, then `end`. The page treats that as an ordinary stop. |
| Dictate right after boot, with a working service | The first attempt gave `aborted` before `audiostart`, the second `audio-capture`, and the third worked. So `aborted` before `audiostart` doesn't by itself mean there is no speech service, and the page keeps Dictate available. |
| Read aloud | `speechSynthesis` is undefined, so the page hides its Read aloud button. |
| The Yard | Renders with WebGL2 at the display's frame rate. |
| Tailscale | The emulator reaches tailnet addresses but doesn't resolve `ts.net` names. With `start.ps1 -Tailnet`, it loads `https://<computer>.<tailnet>.ts.net/api/v1/health` through Tailscale Serve, certificate included. |
| Android 10 system image | Its built-in WebView is 74, without `WEB_MESSAGE_LISTENER` or WebGL2. The app's startup check is tested by controlling the feature check, not by relying on that image (see below). |

The app's instrumented tests then checked these on `ag37`:

| Question | Result |
| --- | --- |
| Does `shouldOverrideUrlLoading` see a form posted to another origin? | No. `onPageStarted` reports the other origin, and stopping there shows the error screen. |
| What does `window.open` do when `onCreateWindow` returns `false`? | Without the user's tap, the popup blocker returns `null` before `onCreateWindow` is called. After a tap, it returns a window that is already `closed`, and nothing is requested from the URL. |
| Does a main-frame redirect to another origin reach `shouldOverrideUrlLoading`? | Yes, with `isRedirect` true; cancelling it means the other origin gets no request. |
| Which origin does `onPermissionRequest` report for a frame's `getUserMedia`? | The frame's. Denied there, the frame's `getUserMedia` rejects with `NotAllowedError`, while the page's own request is granted. |
| What happens after `WebViewRenderProcess.terminate()`? | `onRenderProcessGone`; the new `WebView` loads the page again, which sends `ready` again. |
| Can the page's own QR code be read? | Yes: `paintQr` from `web/remote-access.js` with `web/vendor/qrcode.mjs`, read by ML Kit's bundled model. |
| What does `ag29` show? | The app installs and opens on the unsupported-WebView screen, naming its WebView, 74.0.3729.185. |

## Toolchain

| | Version |
| --- | --- |
| JDK | Temurin 21.0.12.1+1 |
| Gradle | 9.8.2 (the project uses its wrapper) |
| Android Gradle Plugin | 9.4.1 |
| AndroidX WebKit | 1.18.0 |
| compileSdk, targetSdk | 37 |
| minSdk | 29 (Android 10), with the `WEB_MESSAGE_LISTENER` check at startup |
| Build tools | 37.0.0 |
| Emulator images | `google_apis` x86_64 for API 37.0 (`ag37`) and API 29 (`ag29`), and `android-tv` x86_64 for API 36 (`agtv`) |
| Kotlin | 2.2.10, the version the Android Gradle Plugin brings; the Compose compiler plugin matches it |
| Compose BOM | 2026.09.00, with Activity Compose 1.13.0 and Lifecycle Compose 2.10.0 |
| CameraX | 1.6.2 |
| ML Kit barcode scanning (bundled model) | 17.3.0 |
| DataStore | 1.2.1 |
| AndroidX Browser (Custom Tabs) | 1.9.0 |
| Tests | JUnit 4.13.2, kotlinx-coroutines-test 1.11.0, AndroidX Test runner 1.7.0 and ext JUnit 1.3.0, Espresso 3.7.0 |

Versions are in `gradle/libs.versions.toml`. Espresso is named because Compose's test library brings 3.5.0, which calls `InputManager.getInstance`, gone in API 37. These stay as they are unless a concrete compatibility problem calls for a change.

## Emulator tools

Windows, in PowerShell 7. Everything installs into user folders, without administrator rights. The emulator needs the Windows Hypervisor Platform; `setup.ps1` ends with `emulator -accel-check`, which says whether it is usable.

```powershell
pwsh tools/emulator/setup.ps1                       # JDK, Gradle, SDK packages, ag37, ag29 and agtv; skips what is there
. tools/emulator/env.ps1                            # puts them on PATH in this session
pwsh tools/emulator/start.ps1 -Avd ag37 -Tailnet    # headless; -Window to watch it; -Port for a second emulator
pwsh tools/emulator/stop.ps1                        # -Port to match start.ps1
```

- `start.ps1` returns once Android has booted and its network has settled. Right after boot the emulator's network drops for several seconds while Android picks a default network.
- **Always stop an emulator with `stop.ps1`.** A killed emulator leaves a crash report, and its consent dialog hangs the next boot. `start.ps1` clears such leftovers, and `stop.ps1` waits until the emulator's processes have exited, because one started while they run fails to boot.
- `-Tailnet` starts `dns-forward.mjs` on `127.0.0.1:53`. It sends `ts.net` names to Tailscale's MagicDNS resolver, `100.100.100.100`, and everything else to `1.1.1.1`. Starting the emulator with `-dns-server 100.100.100.100` directly breaks every lookup. The host must be signed in to Tailscale.
- `cdp.mjs` evaluates JavaScript in a debuggable `WebView`'s page, after `adb forward tcp:<port> localabstract:webview_devtools_remote_<pid>`.

### The probe

`tools/probe` is a verification app, never shipped. It loads the URL in its `url` extra, registers `agentGuild` for that URL's origin, grants whatever the page asks for, and logs under `AGPROBE`. `site/probe.html` reports the bridge, speech and WebGL; `site/speech.html#<case>` runs the Dictate cases (`normal`, `cancel-early`, `cancel-listening`, `busy`, `restart`, `restart-now`).

```powershell
. tools/emulator/env.ps1
gradle -p tools/probe assembleDebug
adb install -r -g tools/probe/app/build/outputs/apk/debug/app-debug.apk
python -m http.server 8765 --bind 127.0.0.1 --directory tools/probe/site    # in another terminal
adb reverse tcp:8765 tcp:8765
adb shell am start -n com.oddessentials.probe/.MainActivity --es url "'http://127.0.0.1:8765/probe.html'"
adb logcat -s AGPROBE:I
```

## Test boundaries

- **Never touch the manager that is already running on the default port, 47821.** It may be hosting the work itself: never stop, restart or reconfigure it, and never point its remote access elsewhere.
- **Scratch manager:** from an agent-guild checkout or the npm package, with its own data folder and port:

  ```sh
  AGENT_GUILD_HOME=<scratch folder> AGENT_GUILD_PORT=47931 AGENT_GUILD_NO_UPDATE_CHECK=1 node bin/agent-guild.mjs start
  ```

  The token is in `<scratch folder>/auth-token`. `adb reverse tcp:47931 tcp:47931` makes it reachable in the emulator at `http://127.0.0.1:47931`, a Host the manager already allows. Delete the scratch folder afterwards.
- **Tailscale:** signing in over Tailscale needs a Serve route to a scratch manager, which changes the host's Tailscale configuration. Ask before setting one up. Without one, only `GET /api/v1/health`, which needs no token, may be read through the existing route.
- **Unsupported WebView:** the startup check reads `WebViewFeature.isFeatureSupported(WEB_MESSAGE_LISTENER)` through a small interface. Instrumented tests replace it, so the unsupported screen is tested on any image.
- **Tests are deterministic:** no sleeps waiting for timing, and no dependence on the network beyond the scratch manager.
- **Never commit** credentials, tokens, signing keys, `local.properties`, build output or machine-specific paths.

## The app

Kotlin and Compose, package `com.oddessentials.agentguild`, in `app/`:

- `model/`: what the JVM tests cover. `SignInLink` (the parser above), `Origin`, `Health` (the `/api/v1/info` check and its `HealthProblem` messages, which read like the Apple app's), `Bridge`, `Navigation`, `Microphone`, `Recovery`, `Look` (the TV's skin and theme, and the page's skin list), and `Json`, a strict JSON reader: Android's `org.json` accepts single quotes, comments and unquoted names, and isn't available to JVM tests.
- `data/`: the list of computers in DataStore, sealed by `AesGcmSealer`, and `LookStore`.
- `web/PageWeb.kt`: the `WebView`, its clients and the bridge listener. `web/Hooks.kt` holds the startup feature check, the Custom Tab opener and the page events, which instrumented tests replace through `AgentGuildApp`.
- `scan/` and `ui/Scanner.kt`: CameraX and ML Kit's bundled QR model. `CameraChoice` picks the camera: a Meta passthrough camera (vendor tag `com.meta.extra_metadata.camera_source` 0, the left one first), else the first back camera, then the first front one. On a Horizon OS headset, recognized by the two system features Meta's `HorizonOsDetector` checks, it never falls back to the front camera, which shows the wearer's avatar; the scanner says the camera is unavailable instead. On a headset the scanner also asks for `horizonos.permission.HEADSET_CAMERA`, which some of Meta's documentation requires with `CAMERA`; the camera starts once `CAMERA` is granted.
- `ui/`: the computer list (add, rename, sign in again, remove, the demo), Add Computer, the page screen with the TV's Skin and Theme panel (`ui/LookPanel.kt`), and the unsupported-WebView screen. The page screen checks its computer at every `ON_RESUME`: a rejected token or a changed address offers to scan or paste the sign-in link again, and other failures keep the page with a banner. The Yard/Cards choice is saved with the window's state.

Debug builds also accept `http://127.0.0.1:<port>` sign-in links, and allow cleartext only to `127.0.0.1` (`app/src/debug/res/xml/network_security_config.xml`); release builds allow no cleartext.

### Building and testing

```powershell
. tools/emulator/env.ps1
./gradlew.bat :app:testDebugUnitTest                # JVM tests
./gradlew.bat :app:lintDebug                        # must report no errors; it checks every call against minSdk 29
./gradlew.bat :app:assembleDebug
```

The instrumented tests run on `ag37` with a scratch manager (see [Test boundaries](#test-boundaries)) reached through `adb reverse`. They take its token as the `managerToken` argument, and `managerAddress` when it isn't `http://127.0.0.1:47931`:

```powershell
pwsh tools/emulator/start.ps1 -Avd ag37
adb reverse tcp:47931 tcp:47931
$token = (Get-Content <scratch folder>/auth-token).Trim()
./gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.managerToken=$token"
```

- `SignInTest`, `PageTest` and `QrTest` use the scratch manager: sign-in by link, a rejected token, loading, `ready`, the token leaving the address, view switching, a link opening outside, a navigation elsewhere cancelled, `window.open` refused, a new `WebView` after the renderer is terminated, and a sign-in QR code drawn by the manager's own `paintQr` and read through ML Kit.
- `HostileTest` stages what the real manager can't with two servers inside the test process on the device's loopback interface, ports 47941 and 47942: a redirect to another origin, a form posted to another origin, a microphone request from a frame of another origin, and a manager whose `/api/v1/info` answer changes before `ON_RESUME`.
- `StartupTest` replaces the feature check; `StoreTest` uses the Android Keystore. These two and `HealthCheckDeviceTest` need no WebView feature, so they also run on `ag29`.
- `TvTest` and `TvLookTest` replace the TV check, so they run anywhere. `TvLookTest` covers the Skin and Theme panel against a page that applies `&skin=` and `&theme=` as `theme.js` does, a store whose writes fail, and the scratch manager's own skins. `TvRemoteTest` drives the app with remote keys and runs only on a TV such as `agtv`; CI doesn't run it, and elsewhere it is skipped.
- Waits go through the Compose test rule: its clock advances only while the rule synchronizes, so a test thread that blocks any other way stops the screens from changing.

### Later

Each needs its own approval: native Read aloud using `TextToSpeech` and `/api/v1/sessions/:id/reply` (a bridge change), several windows, the compact Yard on a second display, offering a newer release from inside the app, and registering the package and release key for Google's developer verification.

## CI and releases

Pull requests are squash-merged, so a pull request's title becomes its commit on `main`. `pr-title.yml` requires a [Conventional Commit](https://www.conventionalcommits.org/) title, which decides the next version.

### CI

`.github/workflows/ci.yml` runs on every pull request and every push to `main`, on `ubuntu-latest`:

| Job | What it runs |
| --- | --- |
| Build, unit tests and lint | `testDebugUnitTest`, `lintDebug` and `assembleRelease` |
| Signing (disposable key) | Signs the release APK with a key made for the job, as a release does, then `tests/release/apk.mjs` |
| Release plan | `tests/release/plan.mjs` and `tests/release/publish.mjs` |
| Instrumented tests (API 37) | The whole suite, against a scratch manager installed from npm at 1.9.0, the minimum version |
| Instrumented tests (API 29) | `StartupTest`, `StoreTest` and `HealthCheckDeviceTest` |
| Required checks | Passes only when all of the above pass |

- **Emulators:** `ReactiveCircus/android-emulator-runner` boots the `google_apis` x86_64 image of each API level cold, with KVM, and with the emulator pinned to build 16428233 (37.2.12).
- **Readiness and diagnostics:** `tools/ci/device-tests.sh` waits until Android is ready, then runs the tests. When either fails, it saves logcat, a screenshot, the activity stack, the WebView provider, the test reports and the manager's log, as the job's artifact.
- **The token:** the job masks it in its log, the manager's log is saved with tokens replaced, and any saved file that contains the token is deleted.

### Releases

`.github/workflows/release.yml` runs on every push to `main`:

1. **Plan.** semantic-release, as a dry run, plans the version from the titles since the last `v*` tag. `feat` gives a minor release, `fix`, `perf` and `revert` a patch, and `!` a major one; other types release nothing.
2. **Build.** CI runs, and its build makes the release APK with `-PagentGuildVersion=<version>`.
   - `versionCode` is major × 1,000,000 + minor × 1,000 + patch, so it rises with every release.
   - Gradle and the plan refuse a version whose code doesn't fit: minor or patch above 999, or major above 2099.
3. **Sign.** The sign job is the only one with the release key, and it runs no Gradle.
   - `tools/release/apk.mjs` signs the APK with `apksigner`, with v2 and v3 signatures, and no v1 or v4.
   - It then checks that the APK has both signatures, one signer whose certificate is `RELEASE_CERT_SHA256`, the app's package, version name and code, and isn't debuggable.
4. **Publish.** The release job checks the files again, then `tools/release/publish.mjs` publishes them.
   - It creates a draft release holding `agent-guild-<version>.apk` and `SHA256SUMS`, downloads both and compares them with the signed files, then creates the tag and makes the release public.
   - A failed run leaves at most a draft, and re-running its failed jobs finishes it.
   - Anything on GitHub that doesn't match stops the run without changing it, such as a tag on another commit or a draft holding another APK.

The release notes are the changelog: the commits by type, how to install, and the APK's SHA-256. Run by hand on `main`, the workflow signs with the release key but publishes nothing; the signed APK stays a workflow artifact.

The release key lives only on GitHub and in its offline backup:

- the `release-signing` environment, deployable from `main` only, holds the secrets `RELEASE_KEYSTORE_BASE64`, a PKCS12 keystore whose key has the alias `agent-guild`, and `RELEASE_KEYSTORE_PASSWORD`;
- the repository variable `RELEASE_CERT_SHA256` is the SHA-256 of the key's certificate.

Android installs an update only when it is signed with the same key, so losing the key means every user reinstalls.

To check the release tools locally, with `JAVA_HOME` and `ANDROID_HOME` set:

```powershell
npm ci --ignore-scripts --prefix .github/release
node tests/release/plan.mjs
node tests/release/publish.mjs
./gradlew.bat :app:assembleRelease "-PagentGuildVersion=1.2.3"   # PowerShell splits the argument at its dots unless it is quoted
node tests/release/apk.mjs app/build/outputs/apk/release/app-release-unsigned.apk 1.2.3
```

## Limits known now

- **Dictate** needs a speech recognition service on the device. Without one, pressing Dictate ends at once and the page shows nothing. Detecting that natively and telling the page would change the bridge contract, so it needs approval first.
- **Read aloud** is hidden until it is done natively.
- **No notifications or background connection:** the `WebView`'s sockets close when Android stops the app, and the page reconnects when it returns. The sessions keep running on the computer.
- **Meta Horizon OS:** sideloaded only, untested on a headset. Scanning needs a Quest 3 or 3S on Horizon OS v74 or later, with passthrough on; elsewhere, paste the sign-in link. Whether the headset's `WebView` supports `WEB_MESSAGE_LISTENER` is unknown; without it, the app shows its unsupported screen.
- **Emulator and Tailscale:** tailnet names resolve only with `start.ps1 -Tailnet`.

## License

Copyright (c) 2026 Odd Essentials. Open source under the [GNU AGPL 3.0](LICENSE), as Agent Guild.
