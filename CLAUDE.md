# DragSimQol — Fabric client mod (Minecraft 26.1.2)

Client-side macro/QoL mod: Mining Bot (stationary), Route Walker, Zealot combat, ESP, alerts, settings GUI.
Java 25, Fabric Loader 0.19.5, Fabric API 0.155.3+26.1.2, Loom 1.17. Minecraft names are Mojang's (unobfuscated).

## Build & test

Gradle needs JDK 25 (the default `java` on PATH is 8 and fails with "requires JVM 17"):

```bash
export JAVA_HOME="/c/Users/guren/.gradle/jdks/jdk-25.0.4.1+1"
./gradlew compileClientJava --offline -q   # quick compile check
./gradlew test --offline -q                # unit tests (JUnit 5)
./gradlew build --offline -q               # jar -> build/libs/
```

`-q` prints nothing on success. Failures: `grep -l '<failure' build/test-results/test/*.xml`.

**Release builds: bump the version first.** Before building a jar to hand out or release, raise `version` in
`gradle.properties` and commit it. Patch (`1.8.0` → `1.8.1`) for fixes and small adjustments to an existing
feature — e.g. changing how the drill ability timing is obtained, even when it adds a step or a log field.
Minor (`1.8.0` → `1.9.0`) only for a clearly new feature (a new automation, page, overlay or alert type).
When unsure, it's a patch.
Then `./gradlew clean build`, tag `v<version>`, push `main` and the tag to `origin`, and
`gh release create v<version> build/libs/DragSimQol-<version>.jar --repo gurenchang/DragSimQol`
(main jar only, notes in Japanese; cover every change since the last GitHub release). Always pass `--repo`: gh's
default repo here is the upstream fork parent, and without it the release fails or lands on the wrong repo.
Never reuse a released version — a jar that went into the play instance counts as released even without a tag.
Compile checks and test runs don't need a bump.
The jar is named after the repo (`base.archivesName`), the mod id stays `dragsimmacro` (config files, keybinds,
mixin config, lang keys). Jars up to 1.12.1 were `dragsimmacro-<version>.jar`: when installing, the old file must
be deleted from `mods/` — two jars with the same mod id stop the game from starting.
`./gradlew runClient` starts a dev client (game dir `run/`, git-ignored; assets already cached).

## Play instance & replays

The user plays in PrismLauncher: `C:\Users\guren\AppData\Roaming\PrismLauncher\instances\26.1.2 dragsim\minecraft\`
(`mods/` — installed jar plus NoFrills, Recorder, ExploitPreventer etc.; `config/` — live settings;
`logs/zealotmacro.log` — the mod's debug log, `MINING_BOT_*` / `ALERT_*` events with timestamps, previous session in
`zealotmacro.old.log`; `logs/latest.log` — game log with chat; `recordings/`). Read these to check what the user
actually ran.

- `recordings/*.mcrc` come from the Recorder mod (`mods/recorder.jar`, format spec `RECORDING_FORMAT.md` inside it):
  every play packet in both directions plus local key/mouse input, with ms timestamps. `*-buffer.mcrc` are saved
  from the replay buffer (the last minutes before saving); the header's start time maps row times to log times.
  Input rows hold physical keys/buttons only — the mod's own KeyMapping presses show up as packets (START/ABORT,
  SWING, USE_ITEM), not as input rows.
- **`python -I tools/mcrc_report.py`** reports mining from them: blocks/s, break events, own vs area breaks,
  same-block restarts, START cadence, area-break hits, plus the mod log lines in the recording's time window.
  No args = newest recording of at least 3 s; `-n 2` compares the two newest side by side; file names or pasted
  paths work too; `--timeline --from <ms> --to <ms>` dumps the decoded events. Use it when the user sends a
  recording, and compare against the previous one.
- It takes packet ids from `GameProtocols.java` in `mcsrc/<game version>` (registration order) and block state
  ids from the server jar's data reports (generated once into `tools/.cache/`, git-ignored). For packets it doesn't
  decode yet, add them to `events()` the same way.
- Non-mining questions (chat, sounds, entity spawns, particles — e.g. the dragon summon timing): write a scratch
  script outside the repo that imports `mcrc_report` (`read_mcrc`, `packet_ids`, `Buf`, `block_pos`) and decodes
  those packets. Entity / sound / particle registry ids are in `reports/registries.json` of the same data-generator
  run (`java -DbundlerMainClass=net.minecraft.data.Main -jar <loom>/26.1.2/minecraft-server.jar --reports`);
  only `blocks.json` is cached. Chat arrives as NBT components with legacy `§` codes inside the text.

## Looking up Minecraft / Fabric APIs

- Minecraft sources (decompiled): `C:\Users\guren\mcsrc\26.1.2\minecraft` — search there with Grep instead of
  running `javap`. They sit outside the repo on purpose, so normal project searches don't hit them.
  After a Minecraft version bump: `./gradlew genSources`, then unzip
  `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-*/<ver>/*-sources.jar` into a new `mcsrc/<ver>/minecraft`.
- Fabric API has no source jar locally. Use `javap -p` on the jar in
  `~/.gradle/caches/modules-2/files-2.1/net.fabricmc.fabric-api/<module>/` (javap is in the JDK above).

## Layout

Class prefixes: `MiningBot*` = stationary Mining Bot, `RouteWalker*` / `Route*` = Route Walker,
plain `Mining*` = shared by both (`MiningRotationController`, `MiningCameraSmoothing`, `MiningToolCheck`).
Older docs and commits use the former names (`Obsidian*` for the Mining Bot, `Mining*` for the Route Walker);
on-disk names (config files, commands, keybind IDs, log event names) were kept for compatibility.

- `src/main/java/.../DragsimMacroMod.java` — common entrypoint only. Everything else is client code.
- `src/client/java/com/dragsim/macro/client/`
  - `DragsimMacroClient` — wiring: keybinds, commands, tick/render event registration, HUD elements.
  - Mining Bot (`/miningbot`, alias `/obsidian`): `MiningBotController` (state machine), `MiningBotRotation`
    (planned minimum-jerk turns), `MiningBotSettings`, `MiningBotInputSession`, `MiningBotTarget`,
    human-aim layers: `MiningBotAimDrift` (in-block drift), `MiningBotHandTremor` (shake added on output only),
    `MiningBotMouseSteps` (view changes rounded to whole mouse counts at the player's sensitivity),
    `MiningBotLandingPoint` (near-side random landing point), `MiningBotTargetOrder` (sweep-direction habits),
    `MiningBotAreaAvoid` (skip neighbours of a block still breaking — the server's area break may take them),
    `MiningBotAimGuard` (settled crosshair on another block, e.g. bedrock in front: re-aim, then drop),
    `MiningBotAdaptiveAim` / `MiningBotFixedAim` / `MiningBotTickAim` (break-timing lead),
    `DrillActions` (ability/refuel schedule) / `DrillUseSequence` (right-click + sneak key steps; the view holds
    still while refuelling). Refuel clicks once per "Refuelled" reply, 1.0-1.1 s apart: a player holding
    right-click refuels every 4 ticks, but the mod's held Use (1.12.5) and re-presses (1.12.6) got only the
    first refuel — the client sent no further use for ~2 s (replay 2026-10-31 16:51); cause not found yet.
    `DrillAbilityCooldown` / `DrillFuel` (read from the held drill's lore — the ability cooldown there already
    includes the player's reductions and changes with their gear, so don't hard-code it),
    `MiningBotHud` + `MiningBotStats` (HUD panel), `BreakTickMeter` (per-block break time).
    Attempts (and so the mining timeout) start only when vanilla sends START; vanilla's `continueDestroyBlock` keeps
    "continuing" an aborted break on the same block without one, so `allowVanillaAttack` clears that stale state.
  - Route Walker (`/mining`): `RouteWalkerController`, `RouteStore` / `RouteFile` / `RouteNode`, `RouteFollower`,
    `RouteOverlay`, `RouteWalkerSettings`.
  - Combat (Zealot, `/zealot*`): `MacroController`, `TargetSelector`, `RotationController`, `CombatController`,
    `ApproachPlanner` (+ `LocalPathPlanner`/`Path*` A*), `AttackGate`, Frozen Scythe hold-use (`ScytheHold*`).
  - Auto Clicker (GUI toggle on the Combat page): `AutoClicker` queues attack clicks on the vanilla KeyMapping while
    the button is physically held; `AutoClickerSchedule` = random min–max CPS timing.
  - Bonemerang Throw (key only, settings on the Combat page): `BonemerangThrow` throws every hotbar Bonemerang left
    to right — per tick it selects the slot and queues a use click on the vanilla KeyMapping — then selects the
    return slot; `BonemerangVolley` = the tick plan. The server's behaviour is in `BonemerangThrow`'s class comment.
  - Shared: `AutomationCoordinator` (only one automation owns input at a time — `tryAcquire`/`release`),
    `LifecycleSafety` (resets on disconnect/join), `ServerTickClock` (server TPS / tick clock from packets),
    `MacroClock` (monotonic ms clock for client-side timers), `StopInertia` (view coasts to a halt on a manual
    Mining Bot / Route Walker stop), `BreakResetFix` (a server update of the held drill doesn't restart the break).
  - Visuals: `TargetBlockHighlight` (in-world, `LevelRenderEvents.BEFORE_GIZMOS`), `CornerEspOverlay`, `EspOverlay`,
    `DebugHud`, fullbright (`FullbrightLightmapPolicy` + mixin), hit/break particle hiding (`BlockParticlesMixin`).
  - Alerts/feedback: `AlertMonitor` (players, mentions, staff check text/GUI, instance chat (`Instance >`, staff
    only; while a macro runs), server TP/rotate/slot/item/knockback,
    entities spawned nearby, block swaps, server particles / sounds next to you, Mining Bot stalls (no block mined),
    harmful effects, damage, world change, Mining Bot / Route Walker stopping by itself; `OwnMiningTrace` keeps your
    own breaks, area breaks and their regens out of the block-swap alert), `PlayerAlertPolicy`,
    `DesktopNotifier` (Windows toasts via PowerShell; sticky alerts — the macro stopped or can't mine — are reminder
    toasts that stay until dismissed), `AlertFlash` (red screen-edge pulse), `MacroToasts` → `ToastOverlay`,
    `ChatFormat` (chat colours), `MacroNotifications`. `/spawnlog` (`SpawnLogCommand`) prints nearby entity spawns.
    Alert thresholds come from replays / logs of normal play (comments say which); measure again before changing them.
  - Dragon summon: `DragonSpawnTimer` (8th Summoning Eye → dragon in 170 server ticks, spawn point from the frame
    ring; spec measured from replays in `docs/DRAGON_SPAWN_TIMER.ja.md`) → `DragonSpawnOverlay` (countdown HUD,
    marker on the exact spawn point, drawn with depth-test-off render types since the point sits inside the altar's
    structure until the dragon spawns — reuse those for anything that must show through blocks).
  - `MiningSpotWaypointOverlay`: fixed waypoints on the best Amethyst Block / Crying Obsidian spots (found from
    replay chunk data), fade out as you arrive; GUI toggles Amethyst Waypoint / Crying Obsidian Waypoint.
  - Recording mode: `StealthMode` (`MacroConfig.stealthMode`) — the mod draws nothing on screen
    (except the Dragon Spawn Timer and the mining spot waypoints, which have their own toggles — the user's choice).
  - Config (JSON in `config/`): `MacroConfig` → `dragsimmacro.json`; `MiningBotSettings` → `dragsimmacro-miningbot.json`
    (migrates old `dragsimmacro-obsidian.json`); `RouteWalkerSettings` → `dragsimmacro-mining-settings.json`;
    routes → `dragsimmacro-mining.json`. Debug log: `MacroLogger` → `logs/zealotmacro.log`.
  - `mixin/` — listed in `src/main/resources/dragsimmacro.client.mixins.json`.
  - `gui/` — settings screen (see below).
- `src/test/java` — pure-logic tests; the test classpath includes the client source set.
- `docs/*.ja.md` — design notes and specs (Japanese). `tools/gen_gui_icons.py` regenerates GUI icon PNGs,
  `tools/gen_capes.py` the cape textures, `tools/mcrc_report.py` analyses replays (see above).

## GUI conventions

- Recording mode must hide anything new the mod shows: register HUD elements as
  `HudElementRegistry.addLast(id, StealthMode.hide(element))`, guard world rendering with `StealthMode.on()`,
  and send status text through `MacroNotifications.send` (it already skips chat/toasts in recording mode).
  The user plays with recording mode always on, so ask before hiding a feature they asked to see; the Dragon
  Spawn Timer and the mining spot waypoints are the exceptions so far.

- Colours and sizes only from `GuiTheme`. Rounded shapes/icons/buttons via `GuiPaint` (draws in physical pixels).
- Accent tokens (`ACCENT*`, `SIDEBAR_SELECTED`, `ESP_*`) change at runtime with the Theme Color setting
  (`GuiTheme.apply`): read them at draw time, never copy them into `static final` fields.
- Pages are built in `DragsimMacroScreen.build*()` from `GuiWidgets` rows; `GuiContent` groups rows into cards
  (a `SectionHeader` starts a card). Widgets bind to settings via getter/setter lambdas.
- Text uses `UiFont.text/small/title` (Oxanium; one font variant per GUI scale 1–4). Create these components at draw
  time — don't cache them in static fields, or text breaks after a GUI-scale change.

## Working notes

- Code style: tabs; match the surrounding comment density and language (files mix English and Japanese comments).
- When docs (this file, `docs/*.ja.md`, comments) and the existing code disagree, follow the code's way of doing it,
  and list every mismatch you found in your final reply.
- Several source files are 800–1300 lines; read targeted ranges (Grep first, then Read with offset/limit).
- Tests write `MacroLogger` output to `logs/` in the repo root (git-ignored).
- Keep tests lean: test behaviour, not constants or message strings copied back from the source, and not
  vanilla Minecraft itself. When cases only differ in input → expected output, use one table-driven test (see
  `ScytheFireGateTest.eachBlockingConditionWaitsWithItsReason`). Extend an existing test before adding a near-duplicate.

## Git

- Remotes: `origin` = github.com/gurenchang/DragSimQol (push here); `fork` = gurenchang/minecraftdrag (old fork);
  `upstream` = VaLoNIKI/minecraftdrag, fetch-only (push URL disabled on purpose — never push there).
- Work happens on `main` (tracks `origin/main`). Older branches `guren/*`, `codex/*` are historical.
- Commit messages: Conventional Commits in English (`feat:`, `fix:`, `docs:` …).
- `docs/GITHUB_GUIDE.ja.md` is a beginner guide from the fork era; its remote/branch table is outdated.
