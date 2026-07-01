pluginManagement {
	repositories {
		mavenCentral()
		gradlePluginPortal()
		maven("https://maven.fabricmc.net/")
		maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
		maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
	}
	plugins {
		// Pin the Modrinth publishing plugin version centrally; build.gradle.kts
		// applies it without a version (avoids multi-project version clashes).
		id("com.modrinth.minotaur") version "2.+"
	}
}

plugins {
	// Stonecutter: one source tree -> a jar per Minecraft version.
	id("dev.kikugie.stonecutter") version "0.9.6"
	// Applies the correct Loom variant per version (obfuscated <=1.21.x vs the
	// non-obfuscated 26.1+ variant) so a single build.gradle.kts spans them all.
	id("dev.kikugie.loom-back-compat") version "0.3"
	// Lets Gradle auto-download the JDK toolchains (8 / 16 / 17 / 21 / 25) each version needs.
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
	create(rootProject) {
		// Each id must have a matching section in stonecutter.properties.toml.
		// See stonecutter.properties.toml and PORTING.md for the full behavior
		// grouping (HUD API, blit call, registries, non-obf renames) per tier.
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
		vcsVersion = "26.2"
	}
}

rootProject.name = "battlemusic"
