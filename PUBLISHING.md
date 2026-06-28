# Publishing Battle Music (Fabric, multi-version)

This covers shipping every version you build with Stonecutter to Modrinth and
CurseForge, plus an automated GitHub Actions path.

## 0. Build the jars

```bash
./gradlew chiseledBuildAndCollect
```
This builds one jar per node and copies the release jars into
`build/libs/<mod version>/` (e.g. `build/libs/1.2.0/`). Release jars are the ones
WITHOUT the `-sources` suffix, named like `battlemusic-1.2.0+1.20.1.jar`.

> Use `./gradlew` (or the VS Code Gradle panel, which uses the wrapper). A bare
> `gradle` uses the system install (9.4.0) which is too old for Loom 1.17.

## 1. Version naming

Upload one file per Minecraft version. Keep the `modversion+mcversion` scheme so
users can tell them apart:

- `battlemusic-1.2.0+1.20.1.jar`  -> game version 1.20.1
- `battlemusic-1.2.0+1.21.1.jar`  -> game version 1.21.1
- `battlemusic-1.2.0+1.21.8.jar`  -> game versions 1.21.6, 1.21.7, 1.21.8 (mark all that this jar runs on)
- `battlemusic-1.2.0+26.1.2.jar`  -> game version 26.1.x
- `battlemusic-1.2.0+26.2.jar`    -> game version 26.2

For each upload set: loader = **Fabric**, environment = **client**, and the
correct game versions. Declare **Fabric API** as a required dependency and
**Mod Menu** + **Cloth Config** as optional.

## 2. Manual upload

### Modrinth
1. Project -> Versions -> Create version.
2. One version entry per jar (or attach multiple game versions to one jar where
   it genuinely runs on all of them, e.g. 1.21.6-1.21.8).
3. Set channel (release / beta), changelog, dependencies, then publish.

### CurseForge
1. Files -> Upload file.
2. Pick the supported game version(s) and Fabric, add the changelog, publish.

## 3. Automated publishing with the Modrinth Minotaur Gradle plugin

Add Minotaur to `build.gradle.kts` (it applies per node, so each
`./gradlew modrinth` uploads the current node's jar):

```kotlin
plugins {
    id("dev.kikugie.loom-back-compat")
    id("com.modrinth.minotaur") version "2.+"
}

modrinth {
    token.set(providers.environmentVariable("MODRINTH_TOKEN"))
    projectId.set("YOUR_MODRINTH_PROJECT_ID")
    versionNumber.set(project.version.toString())   // e.g. 1.2.0+1.20.1
    versionType.set("release")
    uploadFile.set(loomx.modJar)
    gameVersions.set((sc.properties.rawOrNull("mod", "mc_releases")?.asList().orEmpty()).map { it.toString() })
    loaders.set(listOf("fabric"))
    dependencies {
        required.project("fabric-api")
        optional.project("modmenu")
        optional.project("cloth-config")
    }
}
```
Then, to publish every version:
```bash
MODRINTH_TOKEN=xxxx ./gradlew chiseledBuildAndCollect            # build all
MODRINTH_TOKEN=xxxx ./gradlew "Set active project to 1.20.1" modrinth
# repeat per node, or script the loop
```

For CurseForge, the same Minotaur plugin can also push there - add
`curseforge { ... }` with `CURSEFORGE_TOKEN` and your project id (see the
Minotaur docs).

## 4. Automated publishing with GitHub Actions

`.github/workflows/release.yml` is included and builds all versions on every
push and PR, uploads the jars as workflow artifacts, and creates a GitHub
Release with every jar when you push a `v*` tag (e.g. `git tag v1.2.0 && git push
--tags`). The mod version comes from `mod.version` in
`stonecutter.properties.toml`, so bump it there before tagging. To also publish to Modrinth/CurseForge on a tag, add a step
after the build that runs the Minotaur task per node using repository secrets
`MODRINTH_TOKEN` / `CURSEFORGE_TOKEN` (Settings -> Secrets and variables ->
Actions). `codeql.yml` (restored from your repo) keeps running code scanning.

## 5. About the Dependabot alerts

The Netty / Log4j / SnakeYAML alerts are transitive dependencies pulled in by
Minecraft / Loom for the dev environment. You do not declare or bundle them (no
jar-in-jar `include`), so they do not ship in your mod and Dependabot cannot bump
them (Minecraft pins the versions). Either turn off Dependabot security updates
for this repo, or add a `.github/dependabot.yml` that ignores those transitive
groups. No code change is needed.
