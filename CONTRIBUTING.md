# Contributing

> **English** | [简体中文](CONTRIBUTING.zh-CN.md)

Thanks for looking. This repository takes small, evidenced changes. A pull request that says "it works" without the command it ran and an excerpt of the output will be sent back.

## What this repository is

Development tooling for building **Minecraft 26.3** mods with an agent: a probe mod for the dedicated server and for the client, one orchestration script that drives a whole run, and the DSH plugin that exposes that orchestration as two tools.

It is **not** a mod for players, and it ships **no game files** — no loader, no client, no server, no world. Everything that plays the game is prepared and registered on the contributor's own machine.

| In scope | Out of scope |
|---|---|
| `lab/shared/`, `lab/{fabric,forge,neoforge}/` probe Java | test cases for any specific mod — "what should be tested" is the user's question, not this repository's |
| `scripts/` orchestration (`devrun.ps1`, the install / provision scripts) | shipping a client, a server or a loader |
| `scenarios/` example and self-check scenarios | `instances/` — it holds machine paths (see *Never commit*) |
| `tools/dsh-plugin/mcdev-devrun/` — the two tools and the 11 skills | the records under `docs/`, unless the change is about them |

## Requirements

Windows (the orchestration is PowerShell), **PowerShell 7**, **Node ≥ 20**, **Java 25**, and your own MC 26.3 server + client.

Neither the plugin nor the scripts has a build step or a runtime dependency: `git clone`, then run the checks below.

## Register an instance first

Every end-to-end path starts from a registration, because the suite carries no game:

```powershell
pwsh -File scripts/setup-wizard.ps1        # asks loader / which side / existing-or-new / proxy, writes instances/
pwsh -File scripts/register-instance.ps1   # or describe a machine you already know your way around
```

`instances/` is git-ignored on purpose. Resolve a registration without launching anything:

```powershell
pwsh -File scripts/devrun.ps1 -Family fabric -PreflightOnly
```

## The checks to run before a pull request

Both are offline, need no game, and finish in a second:

```powershell
node tools/dsh-plugin/selftest.mjs                        # registration + package metadata + patch consistency
node tools/dsh-plugin/mcdev-devrun/selftest-skills.mjs    # every skill has a body, every reference resolves
```

CI (`.github/workflows/selftest.yml`) runs exactly these on Node 20 and 22, so a red one is a real failure rather than a local quirk. `publish.yml` runs the first one again before publishing, so the two cannot drift apart.

What they do **not** cover: DSH runtime integration. A plugin change only counts as loaded after DSH is restarted — say whether you did that, or say you did not.

## A real run is the only proof for game-facing changes

```powershell
pwsh -File scripts/devrun.ps1 -Family fabric -Scenario scenarios\smoke.json
```

`-Family` takes `fabric` / `forge` / `neoforge` / `all`; `-Instance` names a registration; `-Scenario` picks the scenario JSON; `-Mods` copies a mod jar into the **test server's** `mods/` (client-side placement is `scripts/install-mod.ps1 -Target <instance> -Side client -Jar <jar>`); `-EvidenceDir` keeps the evidence; `-KeepRunning` leaves the run up for a human to look at.

Prefer the narrowest claim your run supports. "PASS under `-Family fabric`, mod on the server side" is a claim about one loader and one side. Do not generalise it to three loaders or to both sides.

Some of what matters has no data channel — sound, the transition animation, F3 readouts, how a combo feels. Do not assert those from a scenario JSON; say that a human still has to look.

## Claims need evidence

Any "it works" or "it is fixed" in a pull request must carry the command and an excerpt of its real output — raw, not summarised. Numbers must come from an execution, not from the implementation: an assertion that restates the constant it tests proves only that the number was written down twice, and it locks the implementation in place.

## Adding or changing a skill

Skills live in `tools/dsh-plugin/mcdev-devrun/skills/<name>/SKILL.md` (plus `references/` when the body needs a detail sheet) and are read by `skills-provider.mjs`, which keeps a registration list. A directory that is not registered — or a registration without a directory — fails `selftest-skills.mjs` in both directions. A referenced `references/xxx.md` that does not exist fails too.

Both READMEs carry a `skills-N` badge: adding or removing a skill means updating that count in **both** files.

## Documentation is bilingual where it is user-facing

The README ships as a pair — `README.md` and `README.zh-CN.md` — and the two change in the same commit. The records under `docs/` are written in Chinese and are history; they are not translated retroactively.

## Never commit

- `instances/`, `docs/local/` — machine paths, ports, proxy addresses;
- a token, a key, or a `secrets.json` of any kind;
- `dist/`, `tmp/`, `.cache/`, `lab/*/build/`, `lab/*/.gradle/`, `lab/*/run/` — `scripts/package.ps1` rebuilds what matters;
- an evidence directory that contains the game instead of a few dozen KB of logs;
- anything taken from a real session that was not de-identified first.

## Upstream attribution

The probe Java and the plugin began as [`yan-mc/minecraft-mod-dev`](https://github.com/yan-mc/minecraft-mod-dev) (MIT, `Copyright (c) 2026 yan-mc`). [`NOTICE`](NOTICE) and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) carry the notice that licence requires; a patch that removes or trims them will be sent back.

## Reporting instead of patching

Bugs and questions go to [issues](https://github.com/7starsseeker/MCModsDevBridge/issues). A useful report carries the loader, the MC and Java versions, the exact command line, and the evidence directory — or, for a failed assertion, the response body that broke it.

## License

Apache-2.0. By submitting a pull request you agree that it lands under that licence.
