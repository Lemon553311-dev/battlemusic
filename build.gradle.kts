plugins {
	// Applies the correct Loom variant based on the active Minecraft version.
	// (Do not apply fabric-loom directly - loom-back-compat does it for you.)
	id("dev.kikugie.loom-back-compat")
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
	else -> JavaVersion.VERSION_17
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
