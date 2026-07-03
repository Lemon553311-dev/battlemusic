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
	id("net.darkhax.curseforgegradle")
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

	// CI only compiles/packages - it never proves the mod actually boots or
	// works in-game. Unlike Fabric Loom (which auto-registers a default
	// "client" run), ModDevGradle needs this declared explicitly or no run
	// task exists at all. This gives a real way to smoke-test a launch:
	//   ./gradlew :26.1.2-neoforge:runClient
	// (or :26.2-neoforge:runClient). Matches the pattern used by NeoForge's
	// own MDKs and rotgruengelb/stonecutter-mod-template's real 26.x setup.
	runs {
		register("client") {
			client()
			gameDirectory = rootProject.file("run/$mcVersion-neoforge")
			programArgument("--username=Dev")
		}
	}
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
		// Deliberately NOT referencing jar/sourcesJar tasks by name or
		// accessor here at all - every prior attempt at that (bare accessor,
		// reified named<T>(), untyped named() + cast) failed to compile for a
		// different reason each time in real CI. Instead: depend on the
		// standard "build" lifecycle task (guaranteed to exist on every Gradle
		// project) and copy everything Gradle already put in build/libs/,
		// which is the fixed, universal default output directory for archive
		// tasks (jar, sourcesJar) on every JVM project regardless of which
		// loader plugin is applied. This needs zero task-name knowledge.
		dependsOn("build")
		from(layout.buildDirectory.dir("libs"))
		into(rootProject.layout.buildDirectory.dir("libs/$modVersion"))
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

// Mirror of the modrinth block above, for CurseForge. See build.gradle.kts for
// the full commentary. This variant uses the plain `jar` output (ModDevGradle
// has no remapJar) and is always NeoForge.
tasks.register<net.darkhax.curseforgegradle.TaskPublishCurseForge>("publishCurseforge") {
	group = "publishing"
	description = "Uploads this version's jar to CurseForge."
	dependsOn(tasks.jar)

	apiToken = System.getenv("CURSEFORGE_TOKEN") ?: ""
	debugMode = System.getenv("CURSEFORGE_DRY_RUN") == "true"
	disableVersionDetection()

	// project.property(...) qualification is REQUIRED here - see the note in
	// build.gradle.kts's publishCurseforge block (bare property() resolves
	// against the task, not the project, and fails at configuration time).
	val mainFile = upload(
		project.property("mod.curseforge_id") as String,
		tasks.jar.flatMap { it.archiveFile }
	)
	mainFile.displayName = "Battle Music $modVersion ($mcVersion, neoforge)"
	mainFile.releaseType = "release"
	mainFile.changelogType = "markdown"
	mainFile.changelog = System.getenv("CHANGELOG") ?: "See the GitHub release for changes."

	(project.property("mod.mc_releases") as String).split(",").map { it.trim() }
		.forEach { mainFile.addGameVersion(it) }
	mainFile.addModLoader("NeoForge")
	// Required on all new Minecraft files from 2026-07-15; this mod is client-only.
	mainFile.addEnvironment("Client")
	// No Java tag: "Java 25" could not be confirmed in CurseForge's tag list
	// at the time of writing (an unknown tag fails upload validation). Add
	// mainFile.addJavaVersion("Java 25") once confirmed.

	mainFile.addOptional("cloth-config")
}
