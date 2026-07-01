plugins {
	// Applies the correct Loom variant based on the active Minecraft version.
	// (Do not apply fabric-loom directly - loom-back-compat does it for you.)
	id("dev.kikugie.loom-back-compat")
	// Publishes each version's jar to Modrinth via the `modrinth` task.
	// Version is pinned centrally in settings.gradle.kts (pluginManagement).
	id("com.modrinth.minotaur")
}

// Do NOT set group here - loom-back-compat / publishing manage coordinates.
// Version source of truth:
//   - CI release: a git tag like v1.3.0 sets MOD_VERSION=1.3.0 (see release.yml)
//   - Local builds / tagless pushes: falls back to mod.version in
//     stonecutter.properties.toml (currently 1.2.0)
val modVersion: String = System.getenv("MOD_VERSION")
	?.trim()?.removePrefix("v")?.takeIf { it.isNotEmpty() }
	?: property("mod.version") as String

version = "$modVersion+${sc.current.version}"
base.archivesName = property("mod.id") as String

// Each Minecraft version requires a specific Java level.
val requiredJava: JavaVersion = when {
	sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
	sc.current.parsed >= "1.20.5" -> JavaVersion.VERSION_21
	sc.current.parsed >= "1.18" -> JavaVersion.VERSION_17
	sc.current.parsed >= "1.17" -> JavaVersion.VERSION_16
	else -> JavaVersion.VERSION_1_8
}

repositories {
	maven("https://maven.terraformersmc.com/releases") { name = "TerraformersMC" }
	maven("https://maven.shedaniel.me/") { name = "Shedaniel" }
}

dependencies {
	minecraft("com.mojang:minecraft:${sc.current.version}")
	// Official Mojang mappings on obfuscated versions; a no-op on non-obf 26.1+.
	// Keeps class names stable across 1.20.1 / 1.21.x; the few that move at the
	// non-obf switch are handled by the //? gates in the two overlay files.
	loomx.applyMojangMappings()

	// Use mod{...} even on 26.1+ - loom-back-compat converts them as needed.
	modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
	modImplementation("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")

	modImplementation("com.terraformersmc:modmenu:${property("deps.modmenu")}")
	modImplementation("me.shedaniel.cloth:cloth-config-fabric:${property("deps.cloth_config")}") {
		exclude(group = "net.fabricmc.fabric-api")
	}
}

java {
	withSourcesJar()
	sourceCompatibility = requiredJava
	targetCompatibility = requiredJava
	toolchain {
		vendor = JvmVendorSpec.ADOPTIUM
		languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
	}
}

tasks {
	processResources {
		val props = mapOf(
			"id" to project.property("mod.id"),
			"name" to project.property("mod.name"),
			"version" to modVersion,
			"minecraft" to project.property("mod.mc_compat"),
			"loader" to project.property("mod.loader_compat"),
			// Per-tier minimum Java level (see requiredJava above and
			// mod.java_compat in stonecutter.properties.toml) - 1.16 needs Java 8,
			// 1.17 needs 16, 1.18-1.20.4 need 17, 1.20.5+ needs 21, 26.1+ needs 25.
			"java" to project.property("mod.java_compat"),
			// Fabric API's mod id is version-dependent ("fabric" pre-1.19.3,
			// "fabric-api" from 1.19.3 on, with the "fabric" alias removed in 26.1),
			// so it is pinned per tier as mod.fabric_api_id and substituted into
			// fabric.mod.json's depends block via the ${fabric_api_id} placeholder.
			"fabric_api_id" to project.property("mod.fabric_api_id"),
		)
		props.forEach { (key, value) -> inputs.property(key, value) }
		filesMatching("fabric.mod.json") { expand(props) }
	}

	// Builds the active version and copies the release jar(s) into build/libs/<mod
	// version>/. Run buildAndCollect from the root and Gradle runs it across every
	// version subproject at once (no "chiseled" prefix exists in Stonecutter 0.9).
	register<Copy>("buildAndCollect") {
		group = "build"
		description = "Builds the mod and collects jars into build/libs/<mod version>/"
		from(loomx.modJar.flatMap { it.archiveFile }, loomx.modSourcesJar.flatMap { it.archiveFile })
		into(rootProject.layout.buildDirectory.dir("libs/$modVersion"))
		dependsOn("build")
	}
}


// ---------------------------------------------------------------------------
// Modrinth publishing (Minotaur).
// Each Stonecutter version subproject uploads its own jar, tagged with that
// version's Minecraft releases, so `./gradlew modrinth` from the root publishes
// all five versions at once (same whole-tree behaviour as buildAndCollect).
//
// Needed to actually upload (set by release.yml on a v* tag):
//   MODRINTH_TOKEN  - a Modrinth PAT with the "Create versions" scope (GH secret)
//   MOD_VERSION     - e.g. 1.3.0, taken from the git tag
// The target project is mod.modrinth_id in stonecutter.properties.toml.
// With no MODRINTH_TOKEN (normal pushes / PRs) the token stays unset and the
// modrinth task is simply never run, so ordinary builds are unaffected.
// ---------------------------------------------------------------------------
modrinth {
	System.getenv("MODRINTH_TOKEN")?.takeIf { it.isNotBlank() }?.let { token.set(it) }
	projectId.set(property("mod.modrinth_id") as String)

	versionNumber.set("$modVersion+${sc.current.version}")
	versionName.set("Battle Music $modVersion (${sc.current.version})")
	versionType.set("release")

	// The remapped mod jar loom-back-compat produced for this version.
	uploadFile.set(loomx.modJar.flatMap { it.archiveFile })

	// Minecraft releases this jar supports (comma-separated in the toml).
	gameVersions.set(
		(property("mod.mc_releases") as String).split(",").map { it.trim() }
	)
	loaders.add("fabric")

	dependencies {
		required.project("fabric-api")
		optional.project("cloth-config")
		optional.project("modmenu")
	}

	// Set MODRINTH_DRY_RUN=true to validate everything WITHOUT uploading.
	debugMode.set(System.getenv("MODRINTH_DRY_RUN") == "true")
	changelog.set(System.getenv("CHANGELOG") ?: "See the GitHub release for changes.")
}
