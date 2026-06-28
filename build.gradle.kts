plugins {
	// Applies the correct Loom variant based on the active Minecraft version.
	// (Do not apply fabric-loom directly - loom-back-compat does it for you.)
	id("dev.kikugie.loom-back-compat")
}

// Do NOT set group here - loom-back-compat / publishing manage coordinates.
version = "${property("mod.version")}+${sc.current.version}"
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
	modImplementation("net.fabricmc.fabric-api:fabric-api:${sc.properties["deps.fabric_api"]}")

	modImplementation("com.terraformersmc:modmenu:${sc.properties["deps.modmenu"]}")
	modImplementation("me.shedaniel.cloth:cloth-config-fabric:${sc.properties["deps.cloth_config"]}") {
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
			"id" to property("mod.id"),
			"name" to property("mod.name"),
			"version" to property("mod.version"),
			"minecraft" to sc.properties["mod.mc_compat"],
		)
		props.forEach { (key, value) -> inputs.property(key, value) }
		filesMatching("fabric.mod.json") { expand(props) }
	}

	// Builds the active version and copies the release jar(s) into
	// build/libs/<mod version>/. Run the auto-generated chiseledBuildAndCollect
	// to do this for every version at once.
	register<Copy>("buildAndCollect") {
		group = "build"
		description = "Builds the mod and collects jars into build/libs/<mod version>/"
		from(loomx.modJar.flatMap { it.archiveFile }, loomx.modSourcesJar.flatMap { it.archiveFile })
		into(rootProject.layout.buildDirectory.dir("libs/${property("mod.version")}"))
		dependsOn("build")
	}
}
