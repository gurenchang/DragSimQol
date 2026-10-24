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
`./gradlew runClient` starts a dev client (game dir `run/`, git-ignored; assets already cached).

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
    (human-like aim), `MiningBotSettings`, `MiningBotInputSession`, `MiningBotTarget`,
    `MiningBotAdaptiveAim` / `MiningBotFixedAim` / `MiningBotTickAim` (break-timing lead),
    `DrillActions` (ability/refuel schedule) / `DrillUseSequence` (right-click + sneak key steps),
    `MiningBotHud` + `MiningBotStats` (HUD panel), `BreakTickMeter` (per-block break time).
  - Route Walker (`/mining`): `RouteWalkerController`, `RouteStore` / `RouteFile` / `RouteNode`, `RouteFollower`,
    `RouteOverlay`, `RouteWalkerSettings`.
  - Combat (Zealot, `/zealot*`): `MacroController`, `TargetSelector`, `RotationController`, `CombatController`,
    `ApproachPlanner` (+ `LocalPathPlanner`/`Path*` A*), `AttackGate`, Frozen Scythe hold-use (`ScytheHold*`).
  - Shared: `AutomationCoordinator` (only one automation owns input at a time — `tryAcquire`/`release`),
    `LifecycleSafety` (resets on disconnect/join), `ServerTickClock` (server TPS / tick clock from packets),
    `MacroClock` (monotonic ms clock for client-side timers).
  - Visuals: `TargetBlockHighlight` (in-world, `LevelRenderEvents.BEFORE_GIZMOS`), `CornerEspOverlay`, `EspOverlay`,
    `DebugHud`, fullbright (`FullbrightLightmapPolicy` + mixin).
  - Alerts/feedback: `AlertMonitor`, `PlayerAlertPolicy`, `DesktopNotifier`, `AlertFlash` (red screen-edge pulse),
    `MacroToasts` → `ToastOverlay`, `ChatFormat` (chat colours), `MacroNotifications`.
  - Config (JSON in `config/`): `MacroConfig` → `dragsimmacro.json`; `MiningBotSettings` → `dragsimmacro-miningbot.json`
    (migrates old `dragsimmacro-obsidian.json`); `RouteWalkerSettings` → `dragsimmacro-mining-settings.json`;
    routes → `dragsimmacro-mining.json`. Debug log: `MacroLogger` → `logs/zealotmacro.log`.
  - `mixin/` — listed in `src/main/resources/dragsimmacro.client.mixins.json`.
  - `gui/` — settings screen (see below).
- `src/test/java` — pure-logic tests; the test classpath includes the client source set.
- `docs/*.ja.md` — design notes and specs (Japanese). `tools/gen_gui_icons.py` regenerates GUI icon PNGs.

## GUI conventions

- Colours and sizes only from `GuiTheme`. Rounded shapes/icons/buttons via `GuiPaint` (draws in physical pixels).
- Accent tokens (`ACCENT*`, `SIDEBAR_SELECTED`, `ESP_*`) change at runtime with the Theme Color setting
  (`GuiTheme.apply`): read them at draw time, never copy them into `static final` fields.
- Pages are built in `DragsimMacroScreen.build*()` from `GuiWidgets` rows; `GuiContent` groups rows into cards
  (a `SectionHeader` starts a card). Widgets bind to settings via getter/setter lambdas.
- Text uses `UiFont.text/small/title` (Oxanium; one font variant per GUI scale 1–4). Create these components at draw
  time — don't cache them in static fields, or text breaks after a GUI-scale change.

## Working notes

- Code style: tabs; match the surrounding comment density and language (files mix English and Japanese comments).
- Several source files are 800–1300 lines; read targeted ranges (Grep first, then Read with offset/limit).
- Tests write `MacroLogger` output to `logs/` in the repo root (git-ignored).

## Git

- Remotes: `origin` = github.com/gurenchang/DragSimQol (push here); `fork` = gurenchang/minecraftdrag (old fork);
  `upstream` = VaLoNIKI/minecraftdrag, fetch-only (push URL disabled on purpose — never push there).
- Work happens on `main` (tracks `origin/main`). Older branches `guren/*`, `codex/*` are historical.
- Commit messages: Conventional Commits in English (`feat:`, `fix:`, `docs:` …).
- `docs/GITHUB_GUIDE.ja.md` is a beginner guide from the fork era; its remote/branch table is outdated.
