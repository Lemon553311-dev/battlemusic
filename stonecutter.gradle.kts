plugins {
	id("dev.kikugie.stonecutter")
}

// The version that src/ is currently checked out against. The "Set active project
// to ..." Gradle tasks rewrite this line - it is normally managed for you.
stonecutter active "26.2"

// Loader constants for every build target. Java sources can gate on
//   //? if fabric   //? if forge   //? if neoforge
// and combine them with version checks, e.g. //? if forge && >=1.19.
// The loader is the suffix of the target name ("1.20.1-forge"); the original
// suffix-less targets are the Fabric tiers.
// NOTE: if this parameters DSL does not match your Stonecutter 0.9.x exactly,
// see PORTING.md round 8 ("Stonecutter loader constants") for the equivalent
// spellings (constants["forge"] = ... / consts(...) in build.gradle.kts).
stonecutter parameters {
	val target = node.metadata.project
	val loader = target.substringAfterLast('-')
		.takeIf { it == "forge" || it == "neoforge" } ?: "fabric"
	constants.match(loader, "fabric", "forge", "neoforge")
}
