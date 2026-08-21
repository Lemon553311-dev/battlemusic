// Build script for the non-obfuscated Fabric targets (26.1.2, 26.2).
// Mainline Fabric Loom in its non-obf mode - Architectury Loom can't build
// 26.1+ and must never share a script with this plugin (see settings.gradle.kts).
// No mappings() call (nothing is obfuscated) and no modImplementation/remapJar
// (non-obf Loom has no remapping machinery) - plain implementation + jar.

plugins {
	id("net.fabricmc.fabric-loom")
	id("com.modrinth.minotaur")
	id("net.darkhax.curseforgegradle")
}

val mcVersion: String = project.name.substringBeforeLast("-fabric") // "26.1.2" or "26.2"

val modVersion: String = System.getenv("MOD_VERSION")
	?.trim()?.removePrefix("v")?.takeIf { it.isNotEmpty() }
	?: property("mod.version") as String

// Matches the historical Fabric "<mod>+<mc>" scheme used by build.gradle.kts.
version = "$modVersion+$mcVersion-fabric"
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
	// fallback for Mod Menu when the TerraformersMC maven 400s (see build.gradle.kts)
	exclusiveContent {
		forRepository { maven("https://api.modrinth.com/maven") { name = "Modrinth" } }
		filter { includeGroup("maven.modrinth") }
	}
	maven("https://maven.shedaniel.me/") { name = "Shedaniel" }
}

dependencies {
	minecraft("com.mojang:minecraft:$mcVersion")

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
			// 26.1+ removed the old "fabric" alias; only "fabric-api" is valid.
			"fabric_api_id" to "fabric-api",
			"fabric_api_compat" to (project.findProperty("mod.fabric_api_compat") ?: "*"),
		)
		props.forEach { (key, value) -> inputs.property(key, value) }
		filesMatching("fabric.mod.json") { expand(props) }
		exclude("META-INF/mods.toml", "META-INF/neoforge.mods.toml", "pack.mcmeta", "battlemusic_icon.png")
	}

	register<Copy>("buildAndCollect") {
		group = "build"
		description = "Builds the mod and collects jars into build/libs/<mod version>/"
		// depend on "build" and copy build/libs/ - no jar/remapJar task-name
		// knowledge needed (task accessors differ between the loom variants)
		dependsOn("build")
		from(layout.buildDirectory.dir("libs"))
		into(rootProject.layout.buildDirectory.dir("libs/$modVersion"))
	}
}

modrinth {
	System.getenv("MODRINTH_TOKEN")?.takeIf { it.isNotBlank() }?.let { token.set(it) }
	projectId.set(property("mod.modrinth_id") as String)

	versionNumber.set(version.toString())
	versionName.set("Battle Music $modVersion ($mcVersion, fabric)")
	versionType.set("release")

	uploadFile.set(tasks.jar)

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
	mainFile.displayName = "Battle Music $modVersion ($mcVersion, fabric)"
	mainFile.releaseType = "release"
	mainFile.changelogType = "markdown"
	mainFile.changelog = System.getenv("CHANGELOG") ?: "See the GitHub release for changes."

	(project.property("mod.mc_releases") as String).split(",").map { it.trim() }
		.forEach { mainFile.addGameVersion(it) }
	mainFile.addModLoader("Fabric")
	mainFile.addEnvironment("Client")
	// no Java tag: "Java 25" isn't confirmed in CurseForge's tag list yet
	// (an unknown tag fails upload validation)

	mainFile.addRequirement("fabric-api")
	mainFile.addOptional("modmenu")
	mainFile.addOptional("cloth-config")
}
