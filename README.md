# Agent Guild for Android

An app that connects to a computer running [Agent Guild](https://github.com/oddessentials/agent-guild) and shows its Cards and Yard, as [Agent Guild Remote](https://apps.apple.com/us/app/agent-guild-remote/id6825451331) does on iPhone, iPad and Apple Vision Pro.

## Get the app

- [Google Play](https://play.google.com/store/apps/details?id=com.oddessentials.agentguild)
- [Amazon Appstore](https://www.amazon.com/dp/B0HPLBDYPR)
- [Meta Horizon Store](https://www.meta.com/experiences/1325496170657270/)
- [GitHub releases](https://github.com/oddessentials/agent-guild-android/releases/latest), the APK

Every screen Agent Guild runs on is at [agentguild.oddessentials.ai](https://agentguild.oddessentials.ai/).

## Working on it

The code is the reference: the app in `app/`, the emulator and release tools in `tools/`, and CI, releases and store publishing in `.github/workflows/`. The contract between the page and the app is agent-guild's `docs/api.md`.

```powershell
pwsh tools/emulator/setup.ps1     # installs the toolchain and emulators
. tools/emulator/env.ps1          # puts them on PATH in this session
```

## Test boundaries

- **Never touch the manager that is already running.** It may be hosting the work itself: never stop, restart or reconfigure it, and never point its remote access elsewhere. Test against a scratch manager with its own `AGENT_GUILD_HOME` and port, and delete its folder afterwards.
- **Tailscale:** a Serve route to a scratch manager changes the host's Tailscale configuration. Ask before setting one up.
- **Never commit** credentials, tokens, signing keys, `local.properties`, build output or machine-specific paths.

## The release key

Android installs an update only when it is signed with the same key, so losing the release key means every user reinstalls.

## License

Copyright (c) 2026 Odd Essentials. Open source under the [GNU AGPL 3.0](LICENSE), as Agent Guild.
