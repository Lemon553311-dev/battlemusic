plugins {
	// One plugin for Fabric/Forge/NeoForge <=1.21.8; the platform comes from
	// loom.platform in versions/<id>/gradle.properties. Must stay a plain
	// declaration (never "apply false" + apply(plugin=...)) or the Kotlin DSL
	// accessors below stop generating. Cannot do 26.1+ - those targets live in
	// the separate build scripts (see settings.gradle.kts).
	id("dev.architectury.loom")
	id("com.modrinth.minotaur")
	id("net.darkhax.curseforgegradle")
}

// ---- Target coordinates ---------------------------------------------------
// Every Stonecutter target is explicitly named "<mc>-fabric" / "<mc>-forge" /
// "<mc>-neoforge" (see settings.gradle.kts).
val loader: String = project.name.substringAfterLast('-')
val mcVersion: String = project.name.substringBeforeLast('-')

// Numeric per-part comparison, handles "26.1" > "1.21.8".
fun mcAtLeast(v: String): Boolean {
	val a = mcVersion.split('.').map { it.toIntOrNull() ?: 0 }
	val b = v.split('.').map { it.toIntOrNull() ?: 0 }
	for (i in 0 until maxOf(a.size, b.size)) {
		val x = a.getOrElse(i) { 0 }
		val y = b.getOrElse(i) { 0 }
		if (x != y) return x > y
	}
	return true
}

// Version source of truth: MOD_VERSION env (set by CI on a v* tag), falling
// back to mod.version in stonecutter.properties.toml for local builds.
val modVersion: String = System.getenv("MOD_VERSION")
	?.trim()?.removePrefix("v")?.takeIf { it.isNotEmpty() }
	?: property("mod.version") as String

// Fabric keeps the historical "<mod>+<mc>" scheme so published versions stay
// stable; Forge/NeoForge get a loader suffix.
version = "$modVersion+$mcVersion-$loader"
base.archivesName = property("mod.id") as String

// Each Minecraft version requires a specific Java level.
val requiredJava: JavaVersion = when {
	mcAtLeast("26.1") -> JavaVersion.VERSION_25
	mcAtLeast("1.20.5") -> JavaVersion.VERSION_21
	mcAtLeast("1.18") -> JavaVersion.VERSION_17
	mcAtLeast("1.17") -> JavaVersion.VERSION_16
	else -> JavaVersion.VERSION_1_8
}

repositories {
	maven("https://maven.terraformersmc.com/releases") { name = "TerraformersMC" }
	// Fallback for Mod Menu: the TerraformersMC maven intermittently 400s on
	// specific jars. Same jars, group maven.modrinth.
	exclusiveContent {
		forRepository { maven("https://api.modrinth.com/maven") { name = "Modrinth" } }
		filter { includeGroup("maven.modrinth") }
	}
	maven("https://maven.shedaniel.me/") { name = "Shedaniel" }
	maven("https://maven.minecraftforge.net/") { name = "MinecraftForge" }
	maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
	maven("https://maven.architectury.dev/") { name = "Architectury" }
}

dependencies {
	minecraft("com.mojang:minecraft:$mcVersion")
	mappings(loom.officialMojangMappings())

	when (loader) {
		"fabric" -> {
			modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
			modImplementation("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")

			if (findProperty("deps.modmenu_from_modrinth") == "true") {
				modImplementation("maven.modrinth:modmenu:${property("deps.modmenu")}")
			} else {
				modImplementation("com.terraformersmc:modmenu:${property("deps.modmenu")}")
			}
			modImplementation("me.shedaniel.cloth:cloth-config-fabric:${property("deps.cloth_config")}") {
				exclude(group = "net.fabricmc.fabric-api")
			}
		}
		"forge" -> {
			"forge"("net.minecraftforge:forge:$mcVersion-${property("deps.forge")}")
			// compile-time only; optional at runtime
			modImplementation("me.shedaniel.cloth:cloth-config-forge:${property("deps.cloth_config")}")
		}
		"neoforge" -> {
			"neoForge"("net.neoforged:neoforge:${property("deps.neoforge")}")
			modImplementation("me.shedaniel.cloth:cloth-config-neoforge:${property("deps.cloth_config")}")
		}
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
			"loader" to (project.findProperty("mod.loader_compat") ?: ""),
			"java" to project.property("mod.java_compat"),
			// Fabric API's mod id changed over versions; pinned per tier.
			"fabric_api_id" to (project.findProperty("mod.fabric_api_id") ?: "fabric-api"),
			"fabric_api_compat" to (project.findProperty("mod.fabric_api_compat") ?: "*"),
			// ---- Forge / NeoForge metadata (blank on Fabric tiers) ----
			"fml_range" to (project.findProperty("mod.fml_range") ?: ""),
			"loader_dep_id" to (if (loader == "neoforge") "neoforge" else "forge"),
			"loader_range" to (project.findProperty("mod.loader_range") ?: ""),
			"mc_range_maven" to (project.findProperty("mod.mc_range_maven") ?: ""),
			"display_test_line" to (project.findProperty("mod.display_test_line") ?: ""),
			"client_side_only_line" to (project.findProperty("mod.client_side_only_line") ?: ""),
			"pack_format" to (project.findProperty("mod.pack_format") ?: "15"),
		)
		props.forEach { (key, value) -> inputs.property(key, value) }

		when (loader) {
			"fabric" -> {
				filesMatching("fabric.mod.json") { expand(props) }
				exclude("META-INF/mods.toml", "META-INF/neoforge.mods.toml", "pack.mcmeta", "battlemusic_icon.png")
			}
			"forge" -> {
				filesMatching("META-INF/mods.toml") { expand(props) }
				filesMatching("pack.mcmeta") { expand(props) }
				exclude("fabric.mod.json", "META-INF/neoforge.mods.toml")
			}
			"neoforge" -> {
				// mods.toml moved to neoforge.mods.toml at MC 1.20.5.
				if (mcAtLeast("1.20.5")) {
					filesMatching("META-INF/neoforge.mods.toml") { expand(props) }
					exclude("fabric.mod.json", "META-INF/mods.toml")
				} else {
					filesMatching("META-INF/mods.toml") { expand(props) }
					exclude("fabric.mod.json", "META-INF/neoforge.mods.toml")
				}
				filesMatching("pack.mcmeta") { expand(props) }
			}
		}
	}

	// Builds the active version and copies the release jar(s) into
	// build/libs/<mod version>/. Run from the root to build every target.
	register<Copy>("buildAndCollect") {
		group = "build"
		description = "Builds the mod and collects jars into build/libs/<mod version>/"
		from(remapJar.flatMap { it.archiveFile }, remapSourcesJar.flatMap { it.archiveFile })
		into(rootProject.layout.buildDirectory.dir("libs/$modVersion"))
		dependsOn("build")
	}
}


// ---------------------------------------------------------------------------
// Modrinth publishing. `./gradlew modrinth` from the root uploads every jar.
// Needs MODRINTH_TOKEN + MOD_VERSION (set by release.yml on a v* tag);
// without them ordinary builds are unaffected. MODRINTH_DRY_RUN=true to test.
// ---------------------------------------------------------------------------
modrinth {
	System.getenv("MODRINTH_TOKEN")?.takeIf { it.isNotBlank() }?.let { token.set(it) }
	projectId.set(property("mod.modrinth_id") as String)

	versionNumber.set(version.toString())
	versionName.set("Battle Music $modVersion ($mcVersion, $loader)")
	versionType.set("release")

	uploadFile.set(tasks.remapJar.flatMap { it.archiveFile })

	gameVersions.set(
		(property("mod.mc_releases") as String).split(",").map { it.trim() }
	)
	when (loader) {
		"fabric" -> loaders.add("fabric")
		"forge" -> {
			loaders.add("forge")
			// 1.20.1 Forge jar runs on NeoForge 1.20.1 unchanged.
			if (mcVersion == "1.20.1") loaders.add("neoforge")
		}
		"neoforge" -> loaders.add("neoforge")
	}

	dependencies {
		if (loader == "fabric") {
			required.project("fabric-api")
			optional.project("modmenu")
		}
		optional.project("cloth-config")
	}

	debugMode.set(System.getenv("MODRINTH_DRY_RUN") == "true")
	changelog.set(System.getenv("CHANGELOG") ?: "See the GitHub release for changes.")
}

// ---------------------------------------------------------------------------
// CurseForge publishing, mirror of the block above. Needs CURSEFORGE_TOKEN +
// MOD_VERSION; CURSEFORGE_DRY_RUN=true to test. Project id is the NUMERIC id
// from stonecutter.properties.toml, not the slug.
// ---------------------------------------------------------------------------
tasks.register<net.darkhax.curseforgegradle.TaskPublishCurseForge>("publishCurseforge") {
	group = "publishing"
	description = "Uploads this version's jar to CurseForge."
	dependsOn(tasks.remapJar)

	apiToken = System.getenv("CURSEFORGE_TOKEN") ?: ""
	debugMode = System.getenv("CURSEFORGE_DRY_RUN") == "true"

	disableVersionDetection()

	// must be project.property(...) here: inside tasks.register{} a bare
	// property() resolves against the task, not the project
	val mainFile = upload(
		project.property("mod.curseforge_id") as String,
		tasks.remapJar.flatMap { it.archiveFile }
	)
	mainFile.displayName = "Battle Music $modVersion ($mcVersion, $loader)"
	mainFile.releaseType = "release"
	mainFile.changelogType = "markdown"
	mainFile.changelog = System.getenv("CHANGELOG") ?: "See the GitHub release for changes."

	(project.property("mod.mc_releases") as String).split(",").map { it.trim() }
		.forEach { mainFile.addGameVersion(it) }

	when (loader) {
		"fabric" -> mainFile.addModLoader("Fabric")
		"forge" -> {
			mainFile.addModLoader("Forge")
			if (mcVersion == "1.20.1") mainFile.addModLoader("NeoForge")
		}
		"neoforge" -> mainFile.addModLoader("NeoForge")
	}

	mainFile.addEnvironment("Client")

	// only majors confirmed to exist in CurseForge's tag list (an unknown tag
	// fails upload validation)
	val javaMajor = (project.property("mod.java_compat") as String).removePrefix(">=").trim()
	if (javaMajor in listOf("8", "16", "17", "21")) {
		mainFile.addJavaVersion("Java $javaMajor")
	}

	if (loader == "fabric") {
		mainFile.addRequirement("fabric-api")
		mainFile.addOptional("modmenu")
	}
	mainFile.addOptional("cloth-config")
}
