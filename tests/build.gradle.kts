plugins {
	// Deliberately the plain java plugin: this module must never apply a
	// Loom-family plugin (see the "three build scripts" rule in AGENTS.md).
	// It compiles a slice of the mod's SOURCES, not the game.
	java
}

import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

repositories {
	mavenCentral()
}

dependencies {
	// BattleMusicConfig is Gson-based.
	implementation("com.google.code.gson:gson:2.10.1")
	// MusicChannel imports LWJGL (STB Vorbis) types. Compile-time only: the
	// suites never start playback, so no natives are loaded.
	compileOnly("org.lwjgl:lwjgl:3.3.3")
	compileOnly("org.lwjgl:lwjgl-stb:3.3.3")
	testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.5")
	testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.5")
}

// Production slice: copy the game-boot-free sources raw. Stonecutter //?
// files are valid Java as-is (else-branches live by default), which matches
// the 1.21.8 API shape; the few 26.3 one-liner renames are covered by the
// per-target CI compiles, not here. Generation (not a symlink) keeps this
// working on Windows CI runners too.
val prodSlice = layout.buildDirectory.dir("generated-prod-slice")
val copyProdSlice = tasks.register<Copy>("copyProdSlice") {
	from("../src/main/java") {
		include(
			"me/lemon553311/battlemusic/config/BattleMusicConfig.java",
			"me/lemon553311/battlemusic/audio/AudioEngine.java",
			"me/lemon553311/battlemusic/audio/MusicChannel.java",
			"me/lemon553311/battlemusic/audio/MusicLibrary.java",
			"me/lemon553311/battlemusic/detection/AggroTracker.java",
			"me/lemon553311/battlemusic/detection/BossDetector.java",
			"me/lemon553311/battlemusic/detection/HostileStateSignals.java",
			"me/lemon553311/battlemusic/detection/PlayerDamageTracker.java",
			"me/lemon553311/battlemusic/lasttotem/TotemEdgeDetector.java"
		)
	}
	into(prodSlice)
}

sourceSets {
	main {
		java {
			// Hand-written stand-ins for the Minecraft/loader types the slice
			// touches (src/stubs/java), plus the generated production slice.
			// Everything else in src/main/java (bootstraps, screens, render
			// features) is intentionally NOT compiled here.
			srcDir("src/stubs/java")
			srcDir(prodSlice)
		}
	}
}

tasks.named<JavaCompile>("compileJava") {
	dependsOn(copyProdSlice)
}

tasks.named<Test>("test") {
	useJUnitPlatform()
	testLogging {
		events("failed")
		exceptionFormat = TestExceptionFormat.FULL
	}
}
