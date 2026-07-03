plugins {
	// Architectury Loom: one Gradle plugin for Fabric, Forge, and NeoForge.
	// The platform per target comes from versions/<id>/gradle.properties
	// (loom.platform=forge|neoforge; absent = fabric). Version pinned centrally
	// in settings.gradle.kts (pluginManagement).
	//
	// Applied here as a normal, non-"apply false" plugin (NOT imperatively via
	// apply(plugin=...)) because Gradle's Kotlin DSL only generates the
	// type-safe accessors this script relies on (minecraft(...), mappings(...),
	// modImplementation(...), tasks.remapJar, .archiveFile, ...) for plugins
	// declared this way. An earlier revision declared this "apply false" and
	// applied it imperatively per-project to special-case Minecraft 26.1+; that
	// broke EVERY one of those accessors on EVERY target (not just the 26.1+
	// ones), since accessor generation is a compile-time decision based on the
	// static plugins{} block, not on which plugin ends up applied at runtime.
	// See PORTING.md round 8e for the full story.
	//
	// Consequence: Architectury Loom cannot build Minecraft 26.1+ at all (open
	// upstream bug, architectury/architectury-loom#328 - it hard-requires a
	// mappings dependency that does not exist for non-obfuscated Minecraft), so
	// NO 26.1+ target (any loader) is registered in settings.gradle.kts. Fabric
	// coverage stops at 1.21.8, same as Forge/NeoForge.
	id("dev.architectury.loom")
	// Publishes each version's jar to Modrinth via the `modrinth` task.
	id("com.modrinth.minotaur")
	// Publishes each version's jar to CurseForge via the `publishCurseforge` task.
	id("net.darkhax.curseforgegradle")
}

// ---- Target coordinates ---------------------------------------------------
// Every Stonecutter target is explicitly named "<mc>-fabric" / "<mc>-forge" /
// "<mc>-neoforge" (see settings.gradle.kts).
val loader: String = project.name.substringAfterLast('-')
val mcVersion: String = project.name.substringBeforeLast('-')

// Numeric per-part Minecraft version comparison (handles "26.1" > "1.21.8").
// Self-contained so this script does not depend on any plugin's version API.
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

// Version source of truth:
//   - CI release: a git tag like v1.3.0 sets MOD_VERSION=1.3.0 (see release.yml)
//   - Local builds / tagless pushes: falls back to mod.version in
//     stonecutter.properties.toml (currently 1.2.0)
val modVersion: String = System.getenv("MOD_VERSION")
	?.trim()?.removePrefix("v")?.takeIf { it.isNotEmpty() }
	?: property("mod.version") as String

// Fabric keeps the historical "<mod>+<mc>" scheme (published versions stay
// stable); Forge/NeoForge jars get a loader suffix so file names and Modrinth
// version numbers stay unique.
version = if (loader == "fabric") "$modVersion+$mcVersion" else "$modVersion+$mcVersion-$loader"
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
	// Fallback source for Mod Menu: the TerraformersMC maven intermittently
	// rejects artifact downloads (CI got a 400 on modmenu-14.0.0.jar while
	// serving ten other Mod Menu jars fine in the same run). The Modrinth maven
	// serves the identical jars under the maven.modrinth group; the exclusive
	// filter keeps it from ever intercepting other lookups.
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
	// Official Mojang mappings. Every registered target is an obfuscated
	// Minecraft release (<=1.21.8); see the dev.architectury.loom plugin
	// comment above for why 26.1+ (non-obfuscated, no mappings artifact
	// exists) is not supported by this build at all.
	mappings(loom.officialMojangMappings())

	when (loader) {
		"fabric" -> {
			modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
			modImplementation("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")

			// Mod Menu: tiers with deps.modmenu_from_modrinth = true pull the jar
			// from the Modrinth maven (version numbers verified identical there)
			// because the TerraformersMC maven 400'd on those artifacts in CI.
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
			// Forge notation is "<mc>-<forge version>".
			"forge"("net.minecraftforge:forge:$mcVersion-${property("deps.forge")}")
			// Same Cloth Config the Fabric screen uses, Forge edition. Optional at
			// runtime (soft dependency), needed at compile time for the screen class.
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
			// Fabric API's mod id is version-dependent ("fabric" pre-1.19.3,
			// "fabric-api" from 1.19.3 on); pinned per tier as mod.fabric_api_id.
			"fabric_api_id" to (project.findProperty("mod.fabric_api_id") ?: "fabric-api"),
			// Fabric API runtime range for fabric.mod.json's depends block. "*"
			// (any version) everywhere except 1.16.5, which needs a real floor -
			// see stonecutter.properties.toml.
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
				// Keep Fabric jars byte-identical to the pre-multiloader ones.
				// battlemusic_icon.png is the Forge/NeoForge mod-list logo (root
				// copy of assets/battlemusic/icon.png); Fabric uses the assets
				// path from fabric.mod.json, so the root copy is dead weight here.
				exclude("META-INF/mods.toml", "META-INF/neoforge.mods.toml", "pack.mcmeta", "battlemusic_icon.png")
			}
			"forge" -> {
				filesMatching("META-INF/mods.toml") { expand(props) }
				filesMatching("pack.mcmeta") { expand(props) }
				exclude("fabric.mod.json", "META-INF/neoforge.mods.toml")
			}
			"neoforge" -> {
				// NeoForge 20.4 (MC 1.20.4) still reads META-INF/mods.toml; the
				// file moved to META-INF/neoforge.mods.toml at MC 1.20.5.
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

	// Builds the active version and copies the release jar(s) into build/libs/<mod
	// version>/. Run buildAndCollect from the root and Gradle runs it across every
	// version subproject at once.
	register<Copy>("buildAndCollect") {
		group = "build"
		description = "Builds the mod and collects jars into build/libs/<mod version>/"
		from(remapJar.flatMap { it.archiveFile }, remapSourcesJar.flatMap { it.archiveFile })
		into(rootProject.layout.buildDirectory.dir("libs/$modVersion"))
		dependsOn("build")
	}
}


// ---------------------------------------------------------------------------
// Modrinth publishing (Minotaur).
// Each Stonecutter version subproject uploads its own jar, tagged with that
// version's Minecraft releases AND its loader, so `./gradlew modrinth` from the
// root publishes every fabric/forge/neoforge jar at once.
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

	versionNumber.set(version.toString())
	versionName.set("Battle Music $modVersion ($mcVersion, $loader)")
	versionType.set("release")

	// The remapped mod jar Loom produced for this version.
	uploadFile.set(tasks.remapJar.flatMap { it.archiveFile })

	// Minecraft releases this jar supports (comma-separated in the toml).
	gameVersions.set(
		(property("mod.mc_releases") as String).split(",").map { it.trim() }
	)
	when (loader) {
		"fabric" -> loaders.add("fabric")
		// NeoForge 1.20.1 runs Forge 1.20.1 jars unchanged (the fork only
		// diverged at 1.20.2), so the 1.20.1 Forge jar is tagged for both.
		"forge" -> {
			loaders.add("forge")
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

	// Set MODRINTH_DRY_RUN=true to validate everything WITHOUT uploading.
	debugMode.set(System.getenv("MODRINTH_DRY_RUN") == "true")
	changelog.set(System.getenv("CHANGELOG") ?: "See the GitHub release for changes.")
}

// ---------------------------------------------------------------------------
// CurseForge publishing (Darkhax CurseForgeGradle).
// Mirror of the Modrinth block above: same jar, same game versions, same
// loader tagging (1.20.1 Forge doubles as NeoForge 1.20.1), same dependency
// projects (CurseForge identifies them by slug).
//
// Needed to actually upload (set by release.yml on a v* tag):
//   CURSEFORGE_TOKEN - API token from https://authors.curseforge.com/account/api-tokens
//   MOD_VERSION      - e.g. 1.3.0, taken from the git tag
// The target project is mod.curseforge_id in stonecutter.properties.toml: the
// NUMERIC id from the "About Project" box on the project page, not the slug.
// Set CURSEFORGE_DRY_RUN=true to log the upload request instead of sending it.
// ---------------------------------------------------------------------------
tasks.register<net.darkhax.curseforgegradle.TaskPublishCurseForge>("publishCurseforge") {
	group = "publishing"
	description = "Uploads this version's jar to CurseForge."
	dependsOn(tasks.remapJar)

	apiToken = System.getenv("CURSEFORGE_TOKEN") ?: ""
	debugMode = System.getenv("CURSEFORGE_DRY_RUN") == "true"

	// Everything below is declared explicitly; auto-detection would only see
	// the buildtime Minecraft version, not the full supported range.
	disableVersionDetection()

	// NOTE: everything read from stonecutter.properties.toml in this block MUST
	// be qualified as project.property(...). Inside tasks.register<...> {} the
	// lambda receiver is the TASK, and Gradle's Task interface has its own
	// property(String) method, so a bare property(...) looks the key up on the
	// task and fails with "Could not get unknown property ... for task ... of
	// type TaskPublishCurseForge". This never bit the modrinth {} block because
	// extensions have no property() method, so the call falls through to the
	// project there. Caught by the first real CURSEFORGE_DRY_RUN (task
	// registration is lazy, so plain builds never configured this task).
	val mainFile = upload(
		project.property("mod.curseforge_id") as String,
		tasks.remapJar.flatMap { it.archiveFile }
	)
	mainFile.displayName = "Battle Music $modVersion ($mcVersion, $loader)"
	mainFile.releaseType = "release"
	mainFile.changelogType = "markdown"
	mainFile.changelog = System.getenv("CHANGELOG") ?: "See the GitHub release for changes."

	// Same game-version tags as Modrinth. CurseForge uses the same version
	// names for both the 1.x and the new 26.x scheme (verified via their
	// site's version filters).
	(project.property("mod.mc_releases") as String).split(",").map { it.trim() }
		.forEach { mainFile.addGameVersion(it) }

	when (loader) {
		"fabric" -> mainFile.addModLoader("Fabric")
		"forge" -> {
			mainFile.addModLoader("Forge")
			// The 1.20.1 Forge jar also runs on NeoForge 1.20.1 (same tagging
			// as the Modrinth block above).
			if (mcVersion == "1.20.1") mainFile.addModLoader("NeoForge")
		}
		"neoforge" -> mainFile.addModLoader("NeoForge")
	}

	// CurseForge requires an environment tag on all new Minecraft files from
	// 2026-07-15 onward; this mod is client-only.
	mainFile.addEnvironment("Client")

	// Java tag, only for majors confirmed to exist in CurseForge's tag list
	// (an unknown tag fails upload validation; run the publish_test dry run
	// after adding new ones).
	val javaMajor = (project.property("mod.java_compat") as String).removePrefix(">=").trim()
	if (javaMajor in listOf("8", "16", "17", "21")) {
		mainFile.addJavaVersion("Java $javaMajor")
	}

	// Dependencies by CurseForge slug (same projects as on Modrinth).
	if (loader == "fabric") {
		mainFile.addRequirement("fabric-api")
		mainFile.addOptional("modmenu")
	}
	mainFile.addOptional("cloth-config")
}
