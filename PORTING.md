# Battle Music - multi-version porting guide (Fabric)

This project uses **Stonecutter** to build one jar per Minecraft version from a
single source tree. Tier 1 (26.x) is your existing, working code. Tiers 2-3 are
wired up and the version-specific code is gated, but the older-version draw code
is a best-effort starting point that needs a build pass to finalize (it cannot be
compiled in this sandbox).

## What is and isn't version-specific

The entire mod is version-agnostic Java on stable Fabric APIs **except two files**:

- `src/main/java/.../lasttotem/LastTotemFeature.java`
- `src/main/java/.../lastheart/LastHeartFeature.java`

There are **no mixins** anywhere, the audio engine is self-contained, and
detection/state/config/commands use stable events (`ClientTickEvents`,
`ClientLifecycleEvents`, `ClientPlayConnectionEvents`,
`ClientCommandRegistrationCallback`). So all the porting work lives in those two
files, and it is the same three divergences in each.

## The three divergences (per file)

| Concern | 1.20.1 | 1.21.0 - 1.21.4 | 1.21.5 | 1.21.6 - 1.21.x | 26.1+ |
|---|---|---|---|---|---|
| HUD registration | `HudRenderCallback.EVENT.register` | `HudRenderCallback.EVENT.register` | `HudRenderCallback` | `HudElementRegistry.attachElementBefore` | `HudElementRegistry.attachElementBefore` |
| Identifier class | `ResourceLocation` | `ResourceLocation` | `ResourceLocation` | `ResourceLocation` | `Identifier` |
| Identifier factory | `new ResourceLocation(ns,path)` | `ResourceLocation.fromNamespaceAndPath` | `.fromNamespaceAndPath` | `.fromNamespaceAndPath` | `Identifier.fromNamespaceAndPath` |
| GuiGraphics type | `GuiGraphics` | `GuiGraphics` | `GuiGraphics` | `GuiGraphics` | `GuiGraphicsExtractor` |
| Draw call | legacy scaled blit + `RenderSystem` tint | legacy/RenderType blit | `RenderPipelines` blit | `RenderPipelines` blit | `RenderPipelines` blit |
| Java | 17 | 21 | 21 | 21 | 25 |

The Stonecutter predicates used in the code map to those columns:

- `>=1.21.6`  -> new HUD element API, else `HudRenderCallback`.
- `>=1.21.5`  -> `RenderPipelines` blit overload, else legacy blit.
- `>=26.1`    -> `Identifier` / `GuiGraphicsExtractor`, else `ResourceLocation` / `GuiGraphics`.
- `>=1.21`    -> `ResourceLocation.fromNamespaceAndPath`, else `new ResourceLocation(...)`.

## Stonecutter directive syntax (how the gates work)

```java
//? if >=1.21.6 {
activeCodeForNewVersions();
//?} else {
/*inactiveCodeForOldVersions();
*///?}
```

The branch matching the **active** node is uncommented; the others are wrapped in
`/* */`. The files are checked in with the **26.2** branches active (so Tier 1
compiles as-is). When you switch the active node or run `chiseledBuild`,
Stonecutter rewrites the comments so the right branch is live for each version.
`elif` chains multiple conditions (used for the identifier factory three-way).

## The one part that needs your build loop: the legacy draw call

The `>=1.21.5` branch (RenderPipelines) is your known-good 26.x code and is
correct for 1.21.6-1.21.8 too. The `else` branch (1.20.1 / 1.21.0-1.21.4) is the
starting point and is the only thing likely to need adjustment, because the
`GuiGraphics` blit overload shape changed a few times. Reference signatures:

### 1.20.1 - scaled, tinted blit
```java
// GuiGraphics.blit(ResourceLocation, int x, int y, int width, int height,
//                  float u, float v, int regionW, int regionH, int texW, int texH)
RenderSystem.enableBlend();
RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
graphics.blit(IMAGE, drawX, drawY, drawW, drawH, 0f, 0f, IMG_W, IMG_H, IMG_W, IMG_H);
RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
RenderSystem.disableBlend();
```

### 1.21.0 - 1.21.4 - same idea; if the plain overload is gone, use the RenderType one
```java
// graphics.blit(RenderType.guiTextured(ResourceLocation), texture, x, y,
//               u, v, drawW, drawH, regionW, regionH, texW, texH, argbColor)
graphics.blit(
    net.minecraft.client.renderer.RenderType::guiTextured,
    IMAGE, drawX, drawY, 0, 0, drawW, drawH, IMG_W, IMG_H, IMG_W, IMG_H, color);
```
When the ARGB-color overload exists you do NOT need the `RenderSystem` tint lines
(pass the color directly, like the 1.21.5+ branch). Pick whichever overload the
compiler accepts for that exact version and put it in the `else` branch (add an
`elif >=1.21` branch if 1.21.x and 1.20.1 need different code).

### HudRenderCallback signature note
On 1.20.1 the callback hands you `(GuiGraphics, float tickDelta)`; on 1.21.x it is
`(GuiGraphics, DeltaTracker)`. The lambda in `init()` already ignores the second
parameter, so it works for both - no change needed.

## Why official Mojang mappings (mojmap) everywhere

The code is written in mojmap names (`Minecraft`, `LocalPlayer`, `AABB`, ...).
Using `loom.officialMojangMappings()` on every node keeps those names identical
from 1.20.1 to 26.x, so the only names that move are the handful at the 26.1
non-obf switch (`ResourceLocation` -> `Identifier`, `GuiGraphics` ->
`GuiGraphicsExtractor`), which the `>=26.1` gate handles. No Yarn translation is
needed.

## The build loop

In the VS Code "Gradle for Java" panel you can do all of this from the Tasks
list; from a terminal use the wrapper:

```bash
# focus a single version while iterating
./gradlew "Set active project to 1.20.1"
./gradlew build

# build every version at once and collect the jars (CI does this)
./gradlew chiseledBuildAndCollect
```
With `chiseledBuildAndCollect` the release jars land in
`build/libs/<mod version>/` (e.g. `build/libs/1.2.0/`). A single-version `build`
puts its jar in `build/libs/`. Fix any compile error in the gated `else` branch
of the two files, re-run, repeat.

> Always use `./gradlew` (or the panel, which uses the wrapper) - never a bare
> `gradle`. The wrapper is pinned to Gradle 9.6.0, which Loom 1.17 requires.

## Adding more versions later

1. Add the id to `versions(...)` in `settings.gradle.kts`.
2. Add a `["<id>"]` section to `stonecutter.properties.toml` with that version's
   coordinates (copy the nearest existing section and bump the numbers).
3. The existing `//?` gates already cover every era, so the two files usually
   need no further edits.

## Coordinates to confirm before release

The per-version `deps.fabric_api`, `deps.modmenu`, and `deps.cloth_config` in
`stonecutter.properties.toml` are the commonly-correct values but should be
confirmed against Modrinth / the Maven repos (the ones to double-check are marked
`# verify`). The 26.1.2 node uses your known-good values, and the 26.2
`deps.fabric_api` is taken from the current Stonecutter template. Confirm the
26.2 `deps.cloth_config` on
https://maven.shedaniel.me/me/shedaniel/cloth/cloth-config-fabric/.
