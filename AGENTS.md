# AGENTS.md — Battle Music

## What this is

A Minecraft mod (Fabric / Forge / NeoForge, 1.16.5 → 1.21.8 + 26.x Fabric/NeoForge). One source tree builds 28 jars via Stonecutter preprocessor directives (`//? if`). Java, Gradle Kotlin DSL.

## Build & verify

```bash
# Build all 28 targets
./gradlew buildAndCollect

# Build one target (e.g. 1.21.8-fabric)
./gradlew :1.21.8-fabric:buildAndCollect --configure-on-demand --no-daemon

# Smoke test a specific target
./gradlew :1.21.8-fabric:assemble
```

Active Stonecutter version is `1.21.8-fabric` (set in `stonecutter.gradle.kts`). `src/` is checked out against that version; switching it rewrites sources — do not edit `versions/` output trees.

## Multi-loader / version architecture

Three loaders, version-gated with `//? if <loader> [&& <version>]` in Java sources. Loader constants `fabric`, `forge`, `neoforge` come from `stonecutter.gradle.kts`.

- **Entrypoints**:
  - `BattleMusicClient.java` — Fabric `ClientModInitializer` + loader-neutral core (`init()`, static hooks)
  - `BattleMusicForge.java` — Forge bootstrap (1.16.5–1.20.1)
  - `BattleMusicNeoForge.java` — NeoForge bootstrap (1.20.4–26.2)
- **Breaking API boundaries** (gate conditions in Java):
  - `GuiGraphics` exists from 1.20+
  - Blit signature: `ResourceLocation` (<1.21.2) → `Function` (1.21.2–1.21.5) → `RenderPipeline` (≥1.21.6)
  - HUD registration: event callback (<1.21.6) → `HudElementRegistry` (≥1.21.6)
  - Registry: `Registry.ENTITY_TYPE` (<1.19.3) → `BuiltInRegistries.ENTITY_TYPE` (≥1.19.3)
  - Non-obf names: `ResourceLocation`/`GuiGraphics` (<26.1) → `Identifier`/`GuiGraphicsExtractor` (≥26.1)
  - `FMLEnvironment.dist` field removed in NeoForge 21.9 (MC 1.21.9); use `FMLEnvironment.getDist()` ≥26.1
- **1.20.1 special case**: the Forge 1.20.1 jar runs on NeoForge 1.20.1 unchanged (package rename didn't happen until 1.20.2). Published to Modrinth tagged for both loaders.

## 26.1+ build scripts (separate files)

`Architectury Loom` cannot build non-obfuscated Minecraft (open bug architectury/architectury-loom#328). 26.1.2 and 26.2 get their **own** build scripts:

- `build.fabric26.gradle.kts` — mainline `net.fabricmc.fabric-loom`, non-obf mode. Uses plain `implementation` (no `modImplementation`), `tasks.jar` (no `remapJar`). No `mappings()` call.
- `build.neoforge26.gradle.kts` — `net.neoforged.moddev` (ModDevGradle). Same plain `implementation`, `tasks.jar`.

Stonecutter wires them via `.buildscript = "..."` per `version()` registration in `settings.gradle.kts`. Do not add 26.x to `build.gradle.kts`; they never share a script with Architectury Loom.

## Java toolchain per target

Picked automatically in `build.gradle.kts` via `mcAtLeast()`:
```
26.1+ → Java 25
1.20.5+ → Java 21
1.18+ → Java 17
1.17 → Java 16
else → Java 8
```
Adoptium is the required vendor. `org.gradle.jvmargs=-Xmx6G` is set.

## Dependency quirks

- **Fabric API mod-id is version-dependent**: pre-1.19.3 uses `fabric`, 1.19.3+ uses `fabric-api`, 26.1+ only `fabric-api`. Pinned per tier in `stonecutter.properties.toml` → injected into `fabric.mod.json` via `${fabric_api_id}`.
- **Cloth Config Maven versions do NOT carry a `+fabric`/`+forge`/`+neoforge` suffix** even though Modrinth's `version_number` string does. Always trim the suffix when copying from Modrinth.
- **TerraformersMC maven intermittently 400s on Mod Menu jars**. Tiers pinned to `deps.modmenu_from_modrinth = true` pull from `api.modrinth.com/maven` (group `maven.modrinth`) via an `exclusiveContent` filter. Flip the flag for 26.x if the issue recurs.
- **NeoForge 1.20.1**: cloth-config version `11.1.136` (neoforge artifact); Fabric 1.20.1 uses `11.1.136` (fabric artifact). Pin per tier, don't share.

## Publishing (dry-run before releasing)

```bash
# Validates everything without uploading
MODRINTH_DRY_RUN=true ./gradlew :1.21.8-fabric:modrinth
MODRINTH_TOKEN and CURSEFORGE_TOKEN are required for real uploads.
MOD_VERSION (from `git tag vX.Y.Z`) drives the version; falls back to stonecutter.properties.toml otherwise.
```

CI workflow (`release.yml`) builds one target per matrix job, collects jars, then optionally publishes. The `publish_test` workflow_dispatch input runs both Modrinth and CurseForge in dry-run mode.

## Conventions to follow

- `//? if` gates are **flat chains**, never nested. Java block comments cannot nest.
- Multi-loader sources keep the loader-specific logic in the dedicated bootstrap files (`Forge`/`NeoForge`); `BattleMusicClient` holds the shared core.
- Static entrypoints are wired by each loader's bootstrap; the static hook methods (`onClientStarted`, `onEndClientTick`, `onDisconnect`, `onClientStopping`) are the canonical extension points.
- Config is plain JSON via GSON (`config/battlemusic.json`); Cloth Config screen is a soft optional dependency.
- Repository uses `org.slf4j` logger on 1.17+, `org.apache.logging.log4j` on 1.16.5 — gated in `BattleMusicClient`; follow the same pattern in new files.
