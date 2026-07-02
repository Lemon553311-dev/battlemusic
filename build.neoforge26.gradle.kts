// Build script for the two non-obfuscated NeoForge targets (26.1.2, 26.2).
// Architectury Loom cannot build Minecraft 26.1+ at all (see settings.gradle.kts
// and PORTING.md round 8f), so these two targets use ModDevGradle instead -
// NeoForge's OWN official Gradle plugin, which is what NeoForge's own MDKs use
// for 26.1+ and which never had Architectury Loom's mappings requirement in
// the first place (real Forge Config API Port migrated the same way for 26.1).
//
// This is a SEPARATE file from build.gradle.kts on purpose (a documented
// Stonecutter feature - see the .buildscript assignments in settings.gradle.kts),
// so ModDevGradle never shares a script with Architectury Loom. Mixing
// multiple Loom-family plugins in one script previously broke every target's
// DSL accessors (PORTING.md round 8e); physically separate files avoid that.
//
// ModDevGradle is not a Loom fork: it has no modImplementation/remapJar
// concept (NeoForge mods are not remapped the way Fabric mods are), so
// dependencies use plain implementation and the output comes from the
// standard Java `jar` task.

plugins {
	id("net.neoforged.moddev")
	id("com.modrinth.minotaur")
}

val mcVersion: String = project.name.substringBeforeLast("-neoforge") // "26.1.2" or "26.2"

val modVersion: String = System.getenv("MOD_VERSION")
	?.trim()?.removePrefix("v")?.takeIf { it.isNotEmpty() }
	?: property("mod.version") as String

// Loader-suffixed, matching the scheme build.gradle.kts uses for Forge/NeoForge.
version = "$modVersion+$mcVersion-neoforge"
base.archivesName = property("mod.id") as String

java {
	withSourcesJar()
	sourceCompatibility = JavaVersion.VERSION_25
	targetCompatibility = JavaVersion.VERSION_25
	toolchain {
		vendor = JvmVendorSpec.ADOPTIUM
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	maven("https://maven.shedaniel.me/") { name = "Shedaniel" }
}

neoForge {
	version = property("deps.neoforge") as String

	// Client-only mod: no run configs are declared here since CI never runs
	// the game, only compiles and packages it.
}

dependencies {
	// Plain implementation, not modImplementation - ModDevGradle does not
	// remap dependencies the way Fabric Loom does.
	implementation("me.shedaniel.cloth:cloth-config-neoforge:${property("deps.cloth_config")}")
}

tasks {
	processResources {
		val props = mapOf(
			"version" to modVersion,
			"name" to project.property("mod.name"),
			"loader_dep_id" to "neoforge",
			"loader_range" to (project.findProperty("mod.loader_range") ?: ""),
			"mc_range_maven" to (project.findProperty("mod.mc_range_maven") ?: ""),
			"client_side_only_line" to (project.findProperty("mod.client_side_only_line") ?: "clientSideOnly=true"),
			"pack_format" to (project.findProperty("mod.pack_format") ?: "80"),
		)
		props.forEach { (key, value) -> inputs.property(key, value) }
		filesMatching("META-INF/neoforge.mods.toml") { expand(props) }
		filesMatching("pack.mcmeta") { expand(props) }
		exclude("fabric.mod.json", "META-INF/mods.toml")
	}

	register<Copy>("buildAndCollect") {
		group = "build"
		description = "Builds the mod and collects jars into build/libs/<mod version>/"
		// ModDevGradle does not remap; the plain `jar` task is the final
		// artifact. `jar` gets a compile-time accessor (registered
		// unconditionally by the java plugin); `sourcesJar` is created later
		// by withSourcesJar() so it never gets one and must be looked up by
		// name via tasks.named("sourcesJar") - a general Gradle fact, not
		// specific to this plugin. Copy.from() accepts a bare Task/
		// TaskProvider directly and resolves its output files itself.
		from(tasks.jar, tasks.named("sourcesJar"))
		into(rootProject.layout.buildDirectory.dir("libs/$modVersion"))
		dependsOn("build")
	}
}

modrinth {
	System.getenv("MODRINTH_TOKEN")?.takeIf { it.isNotBlank() }?.let { token.set(it) }
	projectId.set(property("mod.modrinth_id") as String)

	versionNumber.set(version.toString())
	versionName.set("Battle Music $modVersion ($mcVersion, neoforge)")
	versionType.set("release")

	// Minotaur's own README shows exactly this form (uploadFile.set(tasks.jar)),
	// warning only that Fabric/Forge Loom (remapping loaders) must use
	// remapJar instead - ModDevGradle has no remapJar, so plain jar is right.
	uploadFile.set(tasks.jar)

	gameVersions.set(
		(property("mod.mc_releases") as String).split(",").map { it.trim() }
	)
	loaders.add("neoforge")

	dependencies {
		optional.project("cloth-config")
	}

	debugMode.set(System.getenv("MODRINTH_DRY_RUN") == "true")
	changelog.set(System.getenv("CHANGELOG") ?: "See the GitHub release for changes.")
}
