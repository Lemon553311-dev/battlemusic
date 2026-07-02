pluginManagement {
	repositories {
		mavenCentral()
		gradlePluginPortal()
		maven("https://maven.fabricmc.net/")
		maven("https://maven.architectury.dev/") { name = "Architectury" }
		maven("https://maven.minecraftforge.net/") { name = "MinecraftForge" }
		maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
		maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
		maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
	}
	plugins {
		// Pin the Modrinth publishing plugin version centrally; build.gradle.kts
		// applies it without a version (avoids multi-project version clashes).
		id("com.modrinth.minotaur") version "2.+"
		// Architectury Loom builds Fabric (<26.1, obfuscated), Forge, AND
		// NeoForge targets from the same build script (the platform comes from
		// each version subproject's loom.platform gradle property; see
		// versions/<id>/gradle.properties). It replaces loom-back-compat, which
		// was Fabric-only. The version tracks the fabric-loom line the project
		// used before (1.17-SNAPSHOT); if this exact version does not resolve,
		// check the latest on https://maven.architectury.dev/dev/architectury/loom/
		// (see PORTING.md).
		//
		// IMPORTANT: Architectury Loom CANNOT build Minecraft 26.1+ at all - it
		// hard-requires a mappings dependency that does not exist for
		// non-obfuscated Minecraft (confirmed open upstream bug,
		// architectury/architectury-loom#328, unresolved as of the latest
		// 1.13 release). A prior revision tried to special-case 26.1+ by also
		// declaring mainline net.fabricmc.fabric-loom and picking between the
		// two per-project; that broke every target's Kotlin DSL accessors
		// (minecraft(...), mappings(...), modImplementation(...), remapJar),
		// not just the 26.1+ ones (see PORTING.md round 8e). No 26.1+ target,
		// for any loader, is registered below; every tier here targets an
		// obfuscated (<=1.21.8) Minecraft release.
		id("dev.architectury.loom") version "1.17-SNAPSHOT"
	}
}

plugins {
	// Stonecutter: one source tree -> a jar per Minecraft version AND loader.
	id("dev.kikugie.stonecutter") version "0.9.6"
	// Lets Gradle auto-download the JDK toolchains (8 / 16 / 17 / 21 / 25) each version needs.
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
	create(rootProject) {
		// Each id must have a matching section in stonecutter.properties.toml.
		// See stonecutter.properties.toml and PORTING.md for the full behavior
		// grouping (HUD API, blit call, registries, non-obf renames) per tier.
		//
		// ---- Fabric (unchanged names, so existing tooling keeps working) ----
		//   1.16.5  -> pre-GuiGraphics, pre-BuiltInRegistries, pre-Warden, Java 8
		//   1.17.1  -> pre-GuiGraphics, Java 16
		//   1.18.2  -> pre-GuiGraphics, Java 17
		//   1.19.2  -> pre-GuiGraphics, pre-BuiltInRegistries, Java 17
		//   1.19.4  -> pre-GuiGraphics, BuiltInRegistries exists, Java 17
		//   1.20.1  -> GuiGraphics + legacy blit, Java 17
		//   1.20.4  -> GuiGraphics + legacy blit, Java 17
		//   1.20.6  -> GuiGraphics + legacy blit, Java 21
		//   1.21.1  -> GuiGraphics + legacy blit, Java 21
		//   1.21.4  -> GuiGraphics + legacy blit, Java 21
		//   1.21.5  -> GuiGraphics + RenderPipelines blit, Java 21
		//   1.21.8  -> new HUD (HudElementRegistry) + RenderPipelines blit, Java 21
		//
		// Stops at 1.21.8, the last obfuscated Minecraft release: Architectury
		// Loom cannot build 26.1+ at all (see the dev.architectury.loom plugin
		// comment above and PORTING.md round 8e).
		versions(
			"1.16.5", "1.17.1", "1.18.2", "1.19.2", "1.19.4",
			"1.20.1", "1.20.4", "1.20.6",
			"1.21.1", "1.21.4", "1.21.5", "1.21.8",
		)

		// ---- Forge (1.16.5 - 1.20.1) --------------------------------------
		// version(name, version): the project is named "<mc>-forge" but the
		// preprocessor still sees the plain Minecraft version, so every //? if
		// version gate keeps working; the loader is exposed as the constants
		// fabric/forge/neoforge (see stonecutter.gradle.kts).
		// The 1.20.1 Forge jar ALSO runs on NeoForge 1.20.1: NeoForge only
		// diverged from Forge (package rename net.minecraftforge -> 
		// net.neoforged) at Minecraft 1.20.2, so no separate 1.20.1-neoforge
		// target is needed.
		version("1.16.5-forge", "1.16.5")
		version("1.17.1-forge", "1.17.1")
		version("1.18.2-forge", "1.18.2")
		version("1.19.2-forge", "1.19.2")
		version("1.19.4-forge", "1.19.4")
		version("1.20.1-forge", "1.20.1")

		// ---- NeoForge (1.20.4 - 1.21.8; 1.20.1 is covered by the Forge jar) --
		// Stops at 1.21.8 (the last obfuscated Minecraft release): NeoForge has
		// no supported Loom-based toolchain for 26.1+ either (see the
		// dev.architectury.loom plugin comment above and PORTING.md round 8d).
		version("1.20.4-neoforge", "1.20.4")
		version("1.20.6-neoforge", "1.20.6")
		version("1.21.1-neoforge", "1.21.1")
		version("1.21.4-neoforge", "1.21.4")
		version("1.21.5-neoforge", "1.21.5")
		version("1.21.8-neoforge", "1.21.8")

		vcsVersion = "1.21.8"
	}
}

rootProject.name = "battlemusic"
