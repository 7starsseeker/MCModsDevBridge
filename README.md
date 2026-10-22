# MC Mods Dev Bridge
[![Awesome DSH Plugin](https://awesome-dsh-plugin.com/badge.svg)](https://awesome-dsh-plugin.com)
[![license: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](./LICENSE)
[![node](https://img.shields.io/badge/node-%E2%89%A520-brightgreen.svg)](https://nodejs.org)
[![DSH plugin](https://img.shields.io/badge/DeepSeek%20Harness-plugin-4B6BFB.svg)](https://github.com/deepseek-ai/deepseek-harness)
[![topic: dsh-plugin](https://img.shields.io/badge/topic-dsh--plugin-4B6BFB.svg)](https://github.com/topics/dsh-plugin)
[![platform](https://img.shields.io/badge/platform-Windows-2f2f2f.svg)](#requirements)
[![Minecraft](https://img.shields.io/badge/Minecraft-26.3-62b47a.svg)](#requirements)
[![loaders](https://img.shields.io/badge/loaders-Fabric%20%7C%20Forge%20%7C%20NeoForge-e8710a.svg)](#requirements)
[![Java](https://img.shields.io/badge/Java-25-important.svg)](#requirements)
[![skills](https://img.shields.io/badge/skills-11-4D6BFE.svg)](./tools/dsh-plugin/mcdev-devrun/skills)
[![selftest](https://img.shields.io/github/actions/workflow/status/7starsseeker/MCModsDevBridge/selftest.yml?label=selftest)](https://github.com/7starsseeker/MCModsDevBridge/actions/workflows/selftest.yml)
[![version](https://img.shields.io/github/v/tag/7starsseeker/MCModsDevBridge?label=version&style=flat)](https://github.com/7starsseeker/MCModsDevBridge/tags)
[![npm](https://img.shields.io/npm/v/mcmodsdevbridge?label=npm&style=flat)](https://www.npmjs.com/package/mcmodsdevbridge)
[![downloads](https://img.shields.io/npm/dm/mcmodsdevbridge?label=downloads&style=flat)](https://www.npmjs.com/package/mcmodsdevbridge)
[![last commit](https://img.shields.io/github/last-commit/7starsseeker/MCModsDevBridge)](https://github.com/7starsseeker/MCModsDevBridge/commits/main)
[![dependencies: 0](https://img.shields.io/badge/dependencies-0-brightgreen.svg)](./package.json)
[![stars](https://img.shields.io/github/stars/7starsseeker/MCModsDevBridge?style=flat)](https://github.com/7starsseeker/MCModsDevBridge/stargazers)
[![issues](https://img.shields.io/github/issues/7starsseeker/MCModsDevBridge)](https://github.com/7starsseeker/MCModsDevBridge/issues)
[![PRs welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](./CONTRIBUTING.md)
[![](https://img.shields.io/badge/powered_by-dsh-4D6BFE?style=flat-square&logo=deepseek&logoColor=white)](https://github.com/deepseek-ai/deepseek-harness)
> **English** | [简体中文](README.zh-CN.md) | [Status](docs/STATE.md) | [Architecture](docs/architecture.md) | [Deployment topology](docs/deployment-topology.md)

Turn "the AI finished writing a mod, and now a human has to click through the game" into one executable pipeline: **start a real test server → start a real client → connect + op → run a scenario JSON → collect evidence → tear down**, across **Fabric / Forge / NeoForge**.

Target: Minecraft **26.3**, Java **25**, Windows.

> This is **development tooling**, not a mod for players. The probe mod it ships exists only so that an agent can see and drive a real game.

---

## What it does

- **Server probe** (`lab/shared/.../ProbeServer.java`): state, commands, blocks / NBT, drops, logs, tick control, action backend; block reads and writes take a `dim` parameter (unknown dimension → 400).
- **Client probe** (`lab/shared/.../ClientProbeServer.java` + `action/ClientActionBackend.java`): UI widget tree, slots, scripted input (click / right-click / keys), screenshots, chat commands, `/chat/tail` (the chat and actionbar the client is **currently** showing, so you can assert "it really appeared on the player's screen").
- **End-to-end orchestration** (`scripts/devrun.ps1`): read the instance registry → start the server → wait for readiness → start the client → connect and op → run the scenario step by step → collect evidence → tear down; non-zero exit code on failure. Steps support `retry` (wait for a state to appear); assertions support exact match (`json`), field contains substring (`contains`), field does **not** contain substring (`notContains`), raw response body contains substring (`text`), and body does **not** contain substring (`textNot`); both probes expose `GET /mods`, which is how a cross-loader setup proves what each side actually loaded. LWJGL natives are extracted **once** into `.cache/natives/<mc version>-<arch>/` (guarded by a file lock), so an evidence directory stays at a few dozen KB of logs instead of ~18 MB.
- **DSH plugin** (`tools/dsh-plugin/mcdev-devrun/`): wraps the same orchestration as the `mcdev_devrun` / `mcdev_scenarios` tools, so an agent can call it directly.

## What it does not do

**It provides capabilities, not answers to "what should be tested".**

- **No test cases for any specific mod.** Which features to test, which text to assert, how fine-grained to go — that depends on what you are building at the time. The JSON files in `scenarios/` are **examples and self-checks**, not a coverage promise and not a recommended list.
- **It does not replace your own hands-on pass.** Sound effects, the purple transition animation, F3 readouts, UI feel, how a combo feels — none of that has a data channel, so a human still has to look at the game. The suite takes over the part that *can* be verified automatically and leaves the human time for the part that only a human can judge.
- **It does not decide "good enough" for you.** A green scenario only means the assertions you wrote hold; it does not mean the feature is complete.

## What this repository does not contain

**No server, client, or loader files.** You prepare those on your own machine, then register them with the wizard:

- `instances/` (instance registry, contains local absolute paths) is **not committed**; `scripts/setup-wizard.ps1` or `scripts/register-instance.ps1` generates it locally.
- `dist/` (build output), `tmp/`, `lab/*/build/`, `lab/*/.gradle/` are ignored too.

## Built on top of

This repository continues the work of **`yan-mc/minecraft-mod-dev`**, and that must be stated explicitly:

| Item | Detail |
|---|---|
| Upstream tool | [yan-mc/minecraft-mod-dev](https://github.com/yan-mc/minecraft-mod-dev) |
| Upstream license | MIT, `Copyright (c) 2026 yan-mc` (full text reproduced in [NOTICE](NOTICE)) |
| Inherited and rewritten | the probe Java in `lab/shared/` + `lab/{fabric,forge,neoforge}/` (from upstream `lab/mcdev-probe/`); `tools/dsh-plugin/` (from upstream `plugin/minecraft-mod-dev/`) |
| Upstream shape | MC 1.20.1 + Forge only + Java 17; tool surface was `mcdev_build` / `mcdev_prepare` / `mcdev_ui` / `mcdev_click` |
| What changed here | MC 26.3; Fabric / Forge / NeoForge; Java 25; "start server + start client + connect + run scenario + collect evidence" collapsed into one `devrun` orchestration layer, with the tool surface `mcdev_devrun` / `mcdev_scenarios` |

Also:

- **The three build skeletons** come from their own upstream MDKs — Fabric `fabric-example-mod` (CC0 1.0), Forge MDK (LGPL 2.1), NeoForge MDK (MIT). The `LICENSE` / `LICENSE.txt` / `TEMPLATE_LICENSE.txt` files inside those skeletons are kept verbatim and are **not** relicensed under this repository's Apache-2.0.
- **The DSH plugin layer** is written against DeepSeek Harness's public plugin API; DSH itself and its packages keep their own licenses.
- Details: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Quick start

<a id="requirements"></a>Requirements: JDK 25; a Minecraft 26.3 server directory you installed yourself (**the level that contains `eula.txt` and `server.properties`**).
Client-only setups do not need a server directory — that is what `side: client` in the instance registry is for.

**Where the probe jar comes from** (`devrun` copies it into the server's `mods\`; the client side goes through `install-probe.ps1`):

- Build it yourself: `lab\fabric\gradlew.bat build` (same for forge / neoforge); the artifact lands in `lab\<loader>\build\libs\mcdev_probe-*.jar`.
- Or use the jar from a release package and pass `-ProbeJar <path>`, or set `probeJar` in the instance JSON.

```powershell
# 1) Register an instance (interactive wizard, or straight from the command line)
pwsh -NoProfile -File scripts\setup-wizard.ps1
pwsh -NoProfile -File scripts\register-instance.ps1 -Name local -Dir '<your server dir>' -Loader fabric

# 2) Environment check only (no game processes)
pwsh -NoProfile -File scripts\devrun.ps1 -Family fabric -PreflightOnly

# 3) Run the smoke scenario
pwsh -NoProfile -File scripts\devrun.ps1 -Instance local -Scenario scenarios\smoke.json
```

Install into DSH (optional; restart DSH afterwards so the plugin loads):

```powershell
pwsh -NoProfile -File scripts\install-dsh-plugin.ps1 -Mode install
```

### Install from the plugin marketplace (optional)

This repository is also a DSH plugin package (`package.json` declares `dsh.bundle`, and the repository root has `cordis.patch.yml`):

```powershell
dsh plugin add github:7starsseeker/MCModsDevBridge
```

Or install the published npm package — a prebuilt tarball, so it needs no build approval step:

```powershell
dsh plugin add mcmodsdevbridge
```

`scripts/`, `scenarios/` and `tools/` are installed together with the package, and **the package root is the workspace root** — the plugin finds it on its own (you can also point `MCDEV_ROOT` somewhere else). Restart DSH after installing. The package still contains **no** Minecraft server / client / loader files: register an instance as described above, or point `-ProbeJar` at a probe jar.

## Where the client comes from

There are three client modes; pick one and write it into the instance registry's `client.mode`:

| `client.mode` | Who starts the client | Good for |
|---|---|---|
| `gradle` | The suite runs this repository's `lab\<loader>` `runClient` task | Changing the probe or the mod **inside this repo**; edits take effect immediately |
| `standalone` | The suite builds a java command line from the **version manifest your launcher left behind** | Suite users: you only have an installed client, no gradle project |
| `external` | You start it yourself | Any launcher, any shape; the suite just waits for the probe to come up |

`standalone` is "the launcher's last step", not another launcher: it reads `versions\<version>\<version>.json` (the self-contained manifest a launcher writes after merging), filters libraries by `rules`, expands `${...}` placeholders, assembles the classpath and arguments, then starts java. It **downloads nothing** and fails loudly when a library is missing.

```powershell
# Install the probe into a client game directory (mods/, config/mcdev-probe.properties, install manifest)
pwsh -NoProfile -File scripts\install-probe.ps1 -Side client -Loader fabric -Target '<TARGET_DIR>\client'
# Uninstall (only files recorded in the manifest whose sha256 still matches; nothing else is touched)
pwsh -NoProfile -File scripts\remove-probe.ps1 -Target '<TARGET_DIR>\client'

# Register an instance: server + standalone client
pwsh -NoProfile -File scripts\register-instance.ps1 -Name local -Dir '<server dir>' -Side both `
     -Client standalone -ClientRoot '<HMCL_HOME>' -ClientVersion '26.3-Fabric' `
     -ClientGameDir '<TARGET_DIR>\client-fabric'
```

**Cross-loader setups** (fabric server + forge client, or the reverse) need an explicit `"loader": "forge"` inside the `client` block:

```jsonc
"client": { "mode": "standalone", "loader": "forge", "version": "26.3-Forge-test", "root": "<HMCL_HOME>" }
```

Without it the client loader defaults to the server `loader`. It decides three things: which `lab\<loader>` project the `gradle` mode runs, which `-Loader` the install hint tells you to use, and the runtime warning when "the client probe reports loader X but the instance declares Y". **The loader is not part of the protocol**, so cross-loader connections do work — but the mod under test must exist for that loader, and a jar for the wrong loader is silently skipped. Only a `/mods` assertion on both sides can catch that.

Things already learned the hard way:

- **The client probe port is fixed at 39002**, so only one client can run at a time (`devrun` clears stale listeners before starting).
- Before starting a standalone client, `devrun` checks `<client dir>\config\mcdev-probe.manifest.json`; if it is missing it fails immediately and tells you to run `install-probe.ps1` first — "client started but probe not installed" would otherwise show up as a probe timeout, which is far more expensive to diagnose.
- A fresh client shows one-time screens first (accessibility onboarding, graphics warnings, …); `devrun` dismisses them according to each screen's own semantics **before** connecting, without writing your `options.txt`, without blind coordinate clicks, and without touching screens it does not recognise.
- `side` decides who is present: `server` (server only) / `client` (client only, nothing to start server-side) / `both`. If a scenario contains steps for a side that is not present, `devrun` fails at **load time**, not halfway through.

### Singleplayer world: the client opens a world itself

"Client-only mods" and "behaviour in singleplayer" cannot be verified on a dedicated server — in singleplayer the client starts an integrated server. The client probe exposes two generic endpoints (nothing mod-specific):

| Endpoint | Purpose |
|---|---|
| `POST /world/singleplayer?name=<id>&mode=normal\|flat&gamemode=survival\|creative&allowCommands=…&seed=…&reuse=true&backup=ask\|skip\|do` | Create or open a singleplayer world; returns `levelId` / `worldDir` / `renamed` / `openedExisting` / `backupAction` |
| `POST /world/leave` | Save and return to the title screen |

- `mode=flat` uses the `FLAT_ALL_DIMENSIONS` preset: flat terrain, fast generation, good for tests; `normal` is regular terrain.
- With `reuse=true`, an existing world of that name is **opened** (same data reused, so `saves` does not grow on every run); otherwise a new one is created, and a name collision gets `-2` / `-3` with `renamed: true`.
- Creation is asynchronous: poll the client `/status` `inWorld` field until it is ready (`scenarios/singleplayer.json` is the example).
- When a run hits `backup=ask`, `devrun` **asks you the question** (three options: write `skip` / write `do` / click it yourself), and `results.json`'s `nextStep.backup` carries the same question — **the suite never chooses for you**. The deployment wizard (`setup-wizard.ps1`) states this rule up front in its completion banner.
- Opening an **existing** singleplayer world makes 26.3 show `BackupConfirmScreen` ("back up then join / skip and join"). `backup=ask` (the default) clicks nothing — whether to back up is your call; `skip` skips the backup and joins (use this in automated scenarios); `do` performs one backup. The action actually taken is reported as `backupAction` (`ask` / `no-screen` / `skipped` / `backed-up` / `still-blocked`).
- The integrated server in a singleplayer world also loads `environment: "*"` mods, so it proves "this works when installed in this client" — it does **not** prove "what happens when the dedicated server does not have this mod". For that you need two instances (each only sees its own `/mods`).

```powershell
# Client-only instance (no server to start; -Dir may be omitted)
pwsh -NoProfile -File scripts\register-instance.ps1 -Name sp -Side client -Loader fabric `
     -Client standalone -ClientRoot '<HMCL_HOME>' -ClientVersion '26.3-Fabric' `
     -ClientGameDir '<TARGET_DIR>\client'
pwsh -NoProfile -File scripts\devrun.ps1 -Instance sp -Scenario scenarios\singleplayer.json
```

### Running a batch of cases: install → run → uninstall

`devrun -Mods` copies jars in for one run; it keeps no ledger and removes nothing. Run a second case and the jars from the previous one are still in `mods\`, so the new assertions no longer know what they are testing. Two scripts close that gap:

- **`scripts/install-mod.ps1`**: installs the mod jar(s) under test into a target directory and records each file's sha256 in `<dir>\config\mcdev-mods.manifest.json`; `-Remove` deletes only files that were recorded *and* whose sha256 still matches (it stops if someone swapped the file; `-Force` overrides); `-List` shows the current state. It also reads Fabric's `environment` field and warns when you install onto the wrong side (for example a `environment: "client"` jar into a server).
- **`scripts/devrun-matrix.ps1 -Cases <file>`**: runs cases serially — install server/client mods → start `devrun` for that scenario → uninstall precisely → read the `PASS=n FAIL=m` line from `summary.md` to decide green/red.

A case file looks like this (`instance` is the name you registered earlier):

```json
{
  "cases": [
    {
      "name": "fixture-both",
      "instance": "fam-fabric",
      "scenario": "scenarios/fixtures/fixture-both.json",
      "serverMods": ["lab/fixtures/fixture/build/libs/mcdev_fixture-1.0.0.jar"],
      "clientMods": ["lab/fixtures/fixture/build/libs/mcdev_fixture-1.0.0.jar"],
      "note": "both sides installed: the handshake works in both directions"
    },
    {
      "name": "expect-missing-half",
      "instance": "fam-fabric",
      "scenario": "scenarios/fixtures/fixture-client-missing.json",
      "serverMods": ["lab/fixtures/fixture/build/libs/mcdev_fixture-1.0.0.jar"],
      "expectFail": true
    }
  ]
}
```

Fields: `name` / `instance` / `scenario` are required; `serverMods` / `clientMods` are jar paths relative to the repository root; `expectFail: true` means "this case is supposed to be red", and only red counts as expected (that is how you state "it must report an error when one half is missing"); `note` only shows up in the summary table. Evidence for each case lands in `<EvidenceDir>\<case name>\`, plus a `matrix-summary.md` at the root. Exit code 0 means every case matched its expectation.

Measured (2026-10-20): the three fixture cases were **3/3 as expected** (`tmp\matrix-fixtures`); a deliberately inverted expectation reported "turned green unexpectedly", and a case with a missing jar was recorded as "crashed" while the run continued (`tmp\matrix-negative`). After every case both `mods\` directories are back to their baseline and no manifest file is left behind.

### Environment resolution

The scripts assume no fixed paths; `scripts/lib/Env.ps1` resolves everything:

| Value | Resolution order |
|---|---|
| java.exe | `-JavaExe` → `$env:JAVA_HOME\bin\java.exe` → `PATH` |
| proxy | `-Proxy` → the registry's `proxy` (asked during deployment) → `$env:HTTPS_PROXY` / `$env:HTTP_PROXY` → the IE proxy from the registry; `-NoProxy` or `noProxy: true` forces a direct connection |

**The proxy is asked once, during deployment — never guessed, never hard-coded**: step 4 of `setup-wizard.ps1` asks "direct / through a proxy (enter the address) / use the system proxy", and the answer is stored in `instances\<name>.json` (`proxy` / `noProxy`); `devrun` and packaging reuse it. Anyone who can get this suite usually already has a proxy, but the port differs per machine — asking once beats guessing.

## Repository layout

| Path | Contents |
|---|---|
| `lab/shared/` | The probe implementation shared by all three loaders (server / client / action backend / commands) |
| `lab/fabric/` `lab/forge/` `lab/neoforge/` | Build skeletons and entry classes for the three loaders |
| `scripts/` | `devrun` orchestration, `devrun-matrix` case matrix, instance registration, deployment wizard, probe install/remove (`install-probe` / `remove-probe`), mod-under-test install/remove (`install-mod`), packaging, DSH plugin install |
| `scripts/lib/` | `Env.ps1` (java / proxy resolution), `Launch.ps1` (builds a client command line from a version manifest) |
| `scenarios/` | Scenario JSON (`smoke` / `server-only` / `actions` / `capabilities` / `singleplayer`; `capabilities` is the suite's own self-check, covering cross-dimension block access, client chat/actionbar assertions and log-substring assertions) |
| `scenarios/fixtures/` | Six sample scenarios paired with `lab/fixtures/` (both sides required / one side missing × client-only / server-only); only used to verify the suite's own assertion abilities |
| `tools/dsh-plugin/` | The DSH plugin (`mcdev_devrun` / `mcdev_scenarios`) |
| `docs/` | Feasibility study, phase-by-phase port records, deployment topology, status overview |
| `docs/examples/` | **Reference material**, not a capability list: one concrete mod demonstrating how to turn "features" into "assertable steps" (see below) |
| `lab/fixtures/` | **The suite's own self-test samples** (five minimal mods: both-sides-required 1, client-only 1, server-only 1, plus Forge/NeoForge builds of the same sample). Their only purpose is to prove that "which side it is installed on" can be asserted as PASS/FAIL; **they are not templates and not example mods** — see [lab/fixtures/README.md](lab/fixtures/README.md) |

Start with [docs/STATE.md](docs/STATE.md) for current progress; [docs/architecture.md](docs/architecture.md) covers the design; [docs/deployment-topology.md](docs/deployment-topology.md) covers instances and port allocation.

## Reference material (not a capability)

[docs/examples/pairedportals/](docs/examples/pairedportals/) is **reference material**: it takes one concrete mod (custom portals paired by sign text) and shows *one way to think* when translating features into automatically assertable steps — how to group them, where assertions belong, how to wait for timing, and what has to be checked by eye. Every step carries a `note` saying what it verifies.

It is **not** part of the suite and is **not** run by `devrun` by default: the suite only provides capabilities, it does not dictate what you test. Before writing cases for another mod, confirm with the user "which features to test, what observable change means success, and what can only be judged by eye", then write the steps.

Judgement comes **only from the probes / logs / APIs** (block state, command feedback, mod logs, client coordinates, …). This tooling does the first-round judgement; it does not replace hands-on testing — sound, animation and feel have no data channel and stay with the human.

## Pairing with DSH computer use (optional)

This suite is the first-round automated test for DSH: it finishes the part that can be judged through probes / logs / APIs and leaves the part that needs eyes to the human. **computer use is an official optional DSH plugin** (`@deepseek-ai/dsh-computer-use` plus its cua-driver MCP provider) — it is not a capability of this suite. This suite **does not implement screenshots**, and does not pretend to simulate a human.

- **At deployment**: `setup-wizard.ps1` checks whether it is enabled in `<DSH_HOME>\profiles\*\cordis.patch.yml`, records the result as `computerUse` in the instance JSON (`enabled` / `disabled` / `absent` / `unknown`), and prints the enablement hint (both package names); `register-instance.ps1 -ComputerUse auto|on|off` sets it explicitly. If DSH home cannot be found, pass `-DshHome` or set `DSH_HOME`.
- **After the first round**: `devrun.ps1` prints a "next step" and writes the same content into `results.json`'s `nextStep` (`ask=true` means you should ask the user) — go straight to manual testing, or run another round with computer use (real mouse, real window, real screenshots).
- **What it is good at**: things outside the game process (launcher, crash dialogs, OS popups, the window itself) and the layer human eyes see (UI look, the purple transition, tooltips).
- **What it costs**: an order of magnitude slower than the probe; a Minecraft 3D window has essentially no UI automation tree, so it works from pixels and a vision model; move the window and every coordinate is invalid.
  So it **is not part of the judgement chain**. It is worth using in exactly two moments: (1) the first round is green and you want one more pass along the real human path, leaving a few images for people to look at; (2) **the first round is red** — `devrun -KeepRunning` leaves the server and client running, then computer use can go in, take screenshots, inspect suspicious spots and read live state, which beats guessing from logs.

## Releasing

The version lives in `package.json`; publishing is automated:

1. bump `version` in `package.json`
2. `git tag -a vX.Y.Z -m "vX.Y.Z"` then `git push origin vX.Y.Z`
3. `.github/workflows/publish.yml` verifies the tag matches the version, runs
   `node tools/dsh-plugin/selftest.mjs`, then publishes with
   `npm publish --provenance --access public` through npm **Trusted Publishing** (OIDC — no tokens, no secrets)

A tag that does not match `package.json` fails the job before anything reaches the registry.

## License

Original code and documentation are released under the **Apache License 2.0** (see [LICENSE](LICENSE) / [NOTICE](NOTICE)); derived parts and skeletons keep their own licenses — see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Minecraft is a trademark of Mojang Synergies AB; this repository does not redistribute the Minecraft client, server, or any game assets.