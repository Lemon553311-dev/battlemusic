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
		id("com.modrinth.minotaur") version "2.+"
		id("net.darkhax.curseforgegradle") version "1.3.32"
		// Builds every <=1.21.8 target on all three loaders (the platform comes
		// from loom.platform in versions/<id>/gradle.properties). Cannot build
		// 26.1+ - it requires mappings that no longer exist there (upstream
		// architectury/architectury-loom#328) - and it must never share a script
		// with the two plugins below: mixing Loom-family plugins in one script
		// breaks Kotlin DSL accessor generation for every target.
		id("dev.architectury.loom") version "1.17-SNAPSHOT"
		id("net.fabricmc.fabric-loom") version "1.17.+"   // build.fabric26.gradle.kts only
		id("net.neoforged.moddev") version "2.0.147"      // build.neoforge26.gradle.kts only
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
		// ---- Fabric ----
		version("1.16.5-fabric", "1.16.5")
		version("1.17.1-fabric", "1.17.1")
		version("1.18.2-fabric", "1.18.2")
		version("1.19.2-fabric", "1.19.2")
		version("1.19.4-fabric", "1.19.4")
		version("1.20.1-fabric", "1.20.1")
		version("1.20.4-fabric", "1.20.4")
		version("1.20.6-fabric", "1.20.6")
		version("1.21.1-fabric", "1.21.1")
		version("1.21.4-fabric", "1.21.4")
		version("1.21.5-fabric", "1.21.5")
		version("1.21.8-fabric", "1.21.8")

		// ---- Forge (1.16.5 - 1.20.1). The 1.20.1 jar also runs on NeoForge
		// 1.20.1: the fork only diverged (package rename) at 1.20.2. ----
		version("1.16.5-forge", "1.16.5")
		version("1.17.1-forge", "1.17.1")
		version("1.18.2-forge", "1.18.2")
		version("1.19.2-forge", "1.19.2")
		version("1.19.4-forge", "1.19.4")
		version("1.20.1-forge", "1.20.1")

		// ---- NeoForge (1.20.4+) ----
		version("1.20.4-neoforge", "1.20.4")
		version("1.20.6-neoforge", "1.20.6")
		version("1.21.1-neoforge", "1.21.1")
		version("1.21.4-neoforge", "1.21.4")
		version("1.21.5-neoforge", "1.21.5")
		version("1.21.8-neoforge", "1.21.8")

		// ---- 26.1+ (non-obfuscated Minecraft): separate build scripts, since
		// Architectury Loom can't build these and must not share a script with
		// the plugins they need. ----
		version("26.1.2-fabric", "26.1.2").buildscript = "build.fabric26.gradle.kts"
		version("26.2-fabric", "26.2").buildscript = "build.fabric26.gradle.kts"
		version("26.3-fabric", "26.3").buildscript = "build.fabric26.gradle.kts"
		version("26.1.2-neoforge", "26.1.2").buildscript = "build.neoforge26.gradle.kts"
		version("26.2-neoforge", "26.2").buildscript = "build.neoforge26.gradle.kts"
		version("26.3-neoforge", "26.3").buildscript = "build.neoforge26.gradle.kts"

		vcsVersion = "1.21.8-fabric"
	}
}

rootProject.name = "battlemusic"
