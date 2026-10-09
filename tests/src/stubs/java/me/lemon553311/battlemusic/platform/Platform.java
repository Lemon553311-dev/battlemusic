package me.lemon553311.battlemusic.platform;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Test stand-in for the loader directory helper. Tests point it at a temp
 * directory per test; production code under test only calls the two methods
 * below.
 */
public final class Platform {
	private static Path root = Paths.get(System.getProperty("java.io.tmpdir"), "battlemusic-tests-default");

	private Platform() {}

	public static void setRoot(Path p) {
		root = p;
	}

	/** .minecraft equivalent. */
	public static Path gameDir() {
		return root;
	}

	/** config/ equivalent. */
	public static Path configDir() {
		return root.resolve("config");
	}
}
