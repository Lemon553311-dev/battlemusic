# Unit tests

Plain-JUnit suites for the game-boot-free logic. No Minecraft download, no
Loom, no game boot: the module compiles a slice of `src/main/java` raw
against hand-written stand-ins, so a full run takes seconds.

## Layout

- `build.gradle.kts` — plain `java` plugin only (never a Loom plugin, per the
  three-build-scripts rule). Copies the production slice into
  `build/generated-prod-slice` and compiles it with `src/stubs/java`.
- `src/stubs/java` — stand-ins for the Minecraft/loader types the slice
  touches (`net.minecraft.*`, plus same-package `BattleMusicClient` and
  `platform/Platform`). Faithful where behavior matters (`Mth.wrapDegrees`,
  distance math); deliberately dumb elsewhere (`ClientLevel` ignores the
  query box; tests control membership explicitly).
- `src/test/java` — the suites, in packages mirroring production.

## Production slice (`copyProdSlice` in build.gradle.kts)

`BattleMusicConfig`, `AudioEngine`, `MusicChannel`, `MusicLibrary`,
`AggroTracker`, `BossDetector`, `HostileStateSignals`,
`PlayerDamageTracker`, `TotemEdgeDetector`.

Stonecutter `//?` files compile as-is (else-branches live by default),
which matches the 1.21.8 API shape. The few 26.3 one-liner renames
(`Enderman`, `isSwinging()`) are covered by the per-target CI compiles,
not here. If you add a `//?` gate to a sliced file, keep the else-branch
compilable against the stubs.

## Running

CI only: the `unit-tests` job in `.github/workflows/release.yml` runs
`./gradlew :tests:test --configure-on-demand --no-daemon`.
It also runs fine locally with the same command (needs network once for
JUnit/Gson/LWJGL jars from Maven Central), but per repo policy we let CI
do it on this small codespace.

## Static state hygiene

Several tested classes hold static state (`MusicLibrary.UNPLAYABLE`,
`Platform` root, `BattleMusicClient` config, the fake registry). Every
suite resets what it touches in `@BeforeEach`/`@Test` setup (usually via
`rescan()`, `setConfig(...)`, `setRoot(...)`, registry `clear()`), because
Gradle may run all suites in one JVM.
