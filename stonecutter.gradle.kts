plugins {
	id("dev.kikugie.stonecutter")
}

// The version that src/ is currently checked out against. The "Set active project
// to ..." Gradle tasks rewrite this line - it is normally managed for you.
stonecutter active "26.2"

// Stonecutter does NOT auto-create chiseled tasks - you register the ones you want.
// A chiseled task runs its target task on EVERY version node in one invocation.
//   chiseledBuild            -> build           (compile + jar every version)
//   chiseledBuildAndCollect  -> buildAndCollect (also copy jars into build/libs/<mod version>/)
// The release workflow calls chiseledBuildAndCollect.
stonecutter registerChiseled tasks.register("chiseledBuild", stonecutter.chiseled) {
	group = "project"
	ofTask("build")
}

stonecutter registerChiseled tasks.register("chiseledBuildAndCollect", stonecutter.chiseled) {
	group = "project"
	ofTask("buildAndCollect")
}
