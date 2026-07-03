plugins {
	id("dev.kikugie.stonecutter")
}

// The version that src/ is currently checked out against. The "Set active project
// to ..." Gradle tasks rewrite this line - it is normally managed for you.
stonecutter active "1.21.8-fabric"

// Loader constants for every build target. Java sources can gate on
//   //? if fabric   //? if forge   //? if neoforge
// and combine them with version checks, e.g. //? if forge && >=1.19.
// Every target is explicitly named "<mc>-fabric" / "<mc>-forge" /
// "<mc>-neoforge" (see settings.gradle.kts), so the loader is always the
// suffix after the last '-'.
// NOTE: if this parameters DSL does not match your Stonecutter 0.9.x exactly,
// see PORTING.md round 8 ("Stonecutter loader constants") for the equivalent
// spellings (constants["forge"] = ... / consts(...) in build.gradle.kts).
stonecutter parameters {
	val target = node.metadata.project
	val loader = target.substringAfterLast('-')
	constants.match(loader, "fabric", "forge", "neoforge")
}
