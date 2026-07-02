// Build script for the two non-obfuscated Fabric targets (26.1.2, 26.2).
// Architectury Loom cannot build Minecraft 26.1+ at all (see settings.gradle.kts
// and PORTING.md round 8f), so these two targets use mainline Fabric Loom
// instead, in its non-obfuscated mode (the plugin id itself, net.fabricmc.
// fabric-loom, denotes non-obf mode on 26.1+; see docs.fabricmc.net/develop/loom).
//
// This is a SEPARATE file from build.gradle.kts on purpose (a documented
// Stonecutter feature - see the .buildscript assignments in settings.gradle.kts)
// so this plugin never shares a script with Architectury Loom. Mixing multiple
// Loom-family plugins in one script previously broke every target's DSL
// accessors (PORTING.md round 8e); physically separate files avoid that
// entirely, since each script's accessors come only from its own plugin.
//
// Deliberately mirrors build.gradle.kts's structure (java toolchain block,
// processResources templating, buildAndCollect, modrinth block) wherever the
// same shape applies, so the two scripts stay easy to compare.

plugins {
	id("net.fabricmc.fabric-loom")
	id("com.modrinth.minotaur")
}

val mcVersion: String = project.name // "26.1.2" or "26.2" (no loader suffix on Fabric)

val modVersion: String = System.getenv("MOD_VERSION")
	?.trim()?.removePrefix("v")?.takeIf { it.isNotEmpty() }
	?: property("mod.version") as String

// Matches the historical Fabric "<mod>+<mc>" scheme used by build.gradle.kts.
version = "$modVersion+$mcVersion"
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
	maven("https://maven.terraformersmc.com/releases") { name = "TerraformersMC" }
	// See build.gradle.kts for why this exists: the TerraformersMC maven
	// intermittently 400s on specific Mod Menu jars.
	exclusiveContent {
		forRepository { maven("https://api.modrinth.com/maven") { name = "Modrinth" } }
		filter { includeGroup("maven.modrinth") }
	}
	maven("https://maven.shedaniel.me/") { name = "Shedaniel" }
}

dependencies {
	minecraft("com.mojang:minecraft:$mcVersion")
	// No mappings() call: Minecraft 26.1+ ships non-obfuscated (real names
	// already), so there is nothing to map - this is the whole reason
	// Architectury Loom cannot build these versions but mainline Loom can.

	// Plain implementation, NOT modImplementation: Fabric Loom's non-obfuscated
	// mode has no remapping machinery at all (there is nothing to remap - see
	// the mappings comment above), so it does not define modImplementation
	// either. Confirmed by a real CI failure (round 8g): only minecraft(...)
	// resolved in this file; every modImplementation call and both remapJar/
	// remapSourcesJar (further below) came back "Unresolved reference".
	implementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
	implementation("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")

	if (findProperty("deps.modmenu_from_modrinth") == "true") {
		implementation("maven.modrinth:modmenu:${property("deps.modmenu")}")
	} else {
		implementation("com.terraformersmc:modmenu:${property("deps.modmenu")}")
	}
	implementation("me.shedaniel.cloth:cloth-config-fabric:${property("deps.cloth_config")}") {
		exclude(group = "net.fabricmc.fabric-api")
	}
}

tasks {
	processResources {
		val props = mapOf(
			"id" to project.property("mod.id"),
			"name" to project.property("mod.name"),
			"version" to modVersion,
			"minecraft" to project.property("mod.mc_compat"),
			"loader" to (project.findProperty("mod.loader_compat") ?: ""),
			"java" to project.property("mod.java_compat"),
			// 26.1+ removed the pre-1.19.3 "fabric" alias entirely; only
			// "fabric-api" is valid here (see stonecutter.properties.toml).
			"fabric_api_id" to "fabric-api",
		)
		props.forEach { (key, value) -> inputs.property(key, value) }
		filesMatching("fabric.mod.json") { expand(props) }
		// Keep this jar's resources scoped to Fabric metadata only.
		exclude("META-INF/mods.toml", "META-INF/neoforge.mods.toml", "pack.mcmeta")
	}

	register<Copy>("buildAndCollect") {
		group = "build"
		description = "Builds the mod and collects jars into build/libs/<mod version>/"
		// No remapJar in non-obf mode either (see the dependencies comment
		// above) - the plain jar/sourcesJar tasks are the final artifacts.
		// Referenced via tasks.named<Jar>(...) with the fully-qualified type
		// (core Gradle API, not plugin-specific sugar) rather than a bare
		// "jar"/"sourcesJar" identifier, since this file has now twice hit
		// cases where an expected bare accessor did not resolve.
		from(
			tasks.named<org.gradle.api.tasks.bundling.Jar>("jar").flatMap { it.archiveFile },
			tasks.named<org.gradle.api.tasks.bundling.Jar>("sourcesJar").flatMap { it.archiveFile },
		)
		into(rootProject.layout.buildDirectory.dir("libs/$modVersion"))
		dependsOn("build")
	}
}

modrinth {
	System.getenv("MODRINTH_TOKEN")?.takeIf { it.isNotBlank() }?.let { token.set(it) }
	projectId.set(property("mod.modrinth_id") as String)

	versionNumber.set(version.toString())
	versionName.set("Battle Music $modVersion ($mcVersion, fabric)")
	versionType.set("release")

	uploadFile.set(tasks.named<org.gradle.api.tasks.bundling.Jar>("jar").flatMap { it.archiveFile })

	gameVersions.set(
		(property("mod.mc_releases") as String).split(",").map { it.trim() }
	)
	loaders.add("fabric")

	dependencies {
		required.project("fabric-api")
		optional.project("modmenu")
		optional.project("cloth-config")
	}

	debugMode.set(System.getenv("MODRINTH_DRY_RUN") == "true")
	changelog.set(System.getenv("CHANGELOG") ?: "See the GitHub release for changes.")
}
