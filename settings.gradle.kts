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
		// Architectury Loom builds Fabric, Forge, AND NeoForge targets from the
		// same build script (the platform comes from each version subproject's
		// loom.platform gradle property; see versions/<id>/gradle.properties).
		// It replaces loom-back-compat, which was Fabric-only. The version tracks
		// the fabric-loom line the project used before (1.17-SNAPSHOT); if this
		// exact version does not resolve, check the latest on
		// https://maven.architectury.dev/dev/architectury/loom/ (see PORTING.md).
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
		//   26.1.2  -> new HUD + RenderPipelines + non-obf names, Java 25
		//   26.2    -> active/dev version, Java 25
		versions(
			"1.16.5", "1.17.1", "1.18.2", "1.19.2", "1.19.4",
			"1.20.1", "1.20.4", "1.20.6",
			"1.21.1", "1.21.4", "1.21.5", "1.21.8",
			"26.1.2", "26.2",
		)

		// ---- Forge (1.16.5 - 1.20.1) --------------------------------------
		// vers(name, version): the project is named "<mc>-forge" but the
		// preprocessor still sees the plain Minecraft version, so every //? if
		// version gate keeps working; the loader is exposed as the constants
		// fabric/forge/neoforge (see stonecutter.gradle.kts).
		// The 1.20.1 Forge jar ALSO runs on NeoForge 1.20.1: NeoForge only
		// diverged from Forge (package rename net.minecraftforge -> 
		// net.neoforged) at Minecraft 1.20.2, so no separate 1.20.1-neoforge
		// target is needed.
		vers("1.16.5-forge", "1.16.5")
		vers("1.17.1-forge", "1.17.1")
		vers("1.18.2-forge", "1.18.2")
		vers("1.19.2-forge", "1.19.2")
		vers("1.19.4-forge", "1.19.4")
		vers("1.20.1-forge", "1.20.1")

		// ---- NeoForge (1.20.4 - 26.2; 1.20.1 is covered by the Forge jar) --
		vers("1.20.4-neoforge", "1.20.4")
		vers("1.20.6-neoforge", "1.20.6")
		vers("1.21.1-neoforge", "1.21.1")
		vers("1.21.4-neoforge", "1.21.4")
		vers("1.21.5-neoforge", "1.21.5")
		vers("1.21.8-neoforge", "1.21.8")
		vers("26.1.2-neoforge", "26.1.2")
		vers("26.2-neoforge", "26.2")

		vcsVersion = "26.2"
	}
}

rootProject.name = "battlemusic"
