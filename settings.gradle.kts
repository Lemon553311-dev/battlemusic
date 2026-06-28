pluginManagement {
	repositories {
		mavenCentral()
		gradlePluginPortal()
		maven("https://maven.fabricmc.net/")
		maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
		maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
	}
}

plugins {
	// Stonecutter: one source tree -> a jar per Minecraft version.
	id("dev.kikugie.stonecutter") version "0.9.6"
	// Applies the correct Loom variant per version (obfuscated <=1.21.x vs the
	// non-obfuscated 26.1+ variant) so a single build.gradle.kts spans them all.
	id("dev.kikugie.loom-back-compat") version "0.3"
	// Lets Gradle auto-download the JDK toolchains (17 / 21 / 25) each version needs.
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
	create(rootProject) {
		// Each id must have a matching section in stonecutter.properties.toml.
		//   1.20.1 -> Tier 3 (old HUD + old blit), Java 17
		//   1.21.1 -> Tier 2 (old HUD + old blit), Java 21
		//   1.21.8 -> Tier 2 (new HUD 1.21.6+ + RenderPipelines blit), Java 21
		//   26.1.2 -> Tier 1 (new HUD + RenderPipelines + non-obf names), Java 25
		//   26.2   -> Tier 1 (active/dev version), Java 25
		versions("1.20.1", "1.21.1", "1.21.8", "26.1.2", "26.2")
		vcsVersion = "26.2"
	}
}

rootProject.name = "battlemusic"
