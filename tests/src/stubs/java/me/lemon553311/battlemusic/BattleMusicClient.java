package me.lemon553311.battlemusic;

import me.lemon553311.battlemusic.config.BattleMusicConfig;

/**
 * Test stand-in for the loader-neutral core. The real BattleMusicClient pulls
 * in Minecraft/loader classes; the tested slice only needs config access,
 * debug logging and a logger, which this provides. Tests run against the
 * REAL BattleMusicConfig class.
 */
public final class BattleMusicClient {
	public static final String MOD_ID = "battlemusic";

	public static final StubLogger LOGGER = new StubLogger();

	private static BattleMusicConfig config = new BattleMusicConfig();

	private BattleMusicClient() {}

	public static BattleMusicConfig config() {
		return config;
	}

	public static void setConfig(BattleMusicConfig c) {
		config = (c != null) ? c : new BattleMusicConfig();
	}

	public static void debug(String format, Object... args) {
		// no-op: production debug logging is off in tests
	}

	/** Minimal logger matching the call shapes production code uses. */
	public static final class StubLogger {
		public void info(String message, Object... args) {}
		public void warn(String message, Object... args) {}
		public void error(String message, Object... args) {}
	}
}
