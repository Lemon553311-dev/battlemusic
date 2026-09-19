// Build script for the non-obfuscated NeoForge targets (26.1.2, 26.2, 26.3).
// ModDevGradle (NeoForge's own plugin) instead of Architectury Loom, which
// can't build 26.1+ and must never share a script with another build plugin
// (see settings.gradle.kts). Not a Loom fork: no modImplementation/remapJar,
// plain implementation + the standard jar task.

plugins {
	id("net.neoforged.moddev")
	id("com.modrinth.minotaur")
	id("net.darkhax.curseforgegradle")
}

val mcVersion: String = project.name.substringBeforeLast("-neoforge") // "26.1.2", "26.2" or "26.3"

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

	// CI never runs the game, but without a runs block ModDevGradle registers
	// no run task at all. Smoke test with:
	//   ./gradlew :26.1.2-neoforge:runClient
	runs {
		register("client") {
			client()
			gameDirectory = rootProject.file("run/$mcVersion-neoforge")
			programArgument("--username=Dev")
		}
	}
}

dependencies {
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
		// depend on "build" and copy build/libs/ - no task-name knowledge needed
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

// CurseForge mirror of the block above.
tasks.register<net.darkhax.curseforgegradle.TaskPublishCurseForge>("publishCurseforge") {
	group = "publishing"
	description = "Uploads this version's jar to CurseForge."
	dependsOn(tasks.jar)

	apiToken = System.getenv("CURSEFORGE_TOKEN") ?: ""
	debugMode = System.getenv("CURSEFORGE_DRY_RUN") == "true"
	disableVersionDetection()

	// must be project.property(...) here - bare property() resolves against the task
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
	mainFile.addEnvironment("Client")
	// no Java tag: "Java 25" isn't confirmed in CurseForge's tag list yet

	mainFile.addOptional("cloth-config")
}
