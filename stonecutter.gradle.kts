plugins {
	id("dev.kikugie.stonecutter")
}

// The version src/ is currently checked out against. Rewritten by the
// "Set active project to ..." Gradle tasks.
stonecutter active "1.21.8-fabric"

// Loader constants for the //? gates: fabric / forge / neoforge, taken from
// the "<mc>-<loader>" project name suffix. Combine with version checks like
// //? if forge && >=1.19.
stonecutter parameters {
	val target = node.metadata.project
	val loader = target.substringAfterLast('-')
	constants.match(loader, "fabric", "forge", "neoforge")
}
