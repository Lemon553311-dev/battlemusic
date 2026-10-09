package me.lemon553311.battlemusic.config;

import me.lemon553311.battlemusic.platform.Platform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Config defaults, clamp ranges, NaN robustness and save/load round-trips.
 * Uses the REAL BattleMusicConfig against a temp Platform root.
 */
class BattleMusicConfigTest {

	@TempDir
	Path temp;

	@BeforeEach
	void setUp() {
		Platform.setRoot(temp);
	}

	@Test
	void defaults_matchDocumentedValues() {
		BattleMusicConfig c = new BattleMusicConfig();
		assertEquals(25.0D, c.detectionRadius);
		assertEquals(5, c.aggroMobCount);
		assertEquals(15.0D, c.playerCombatTimeoutSeconds);
		assertEquals(7.0D, c.fadeOutDelaySeconds);
		assertEquals(7.0D, c.fadeOutDurationSeconds);
		assertEquals(3.0D, c.fadeInDurationSeconds);
		assertEquals(30.0D, c.resumeWithinSeconds);
		assertEquals(3, c.resumeAggroMobCount);
		assertEquals(1.0D, c.regularFolderVolume);
		assertEquals(1.0D, c.heavyFolderVolume);
		assertTrue(c.enabled);
		assertEquals(BattleMusicConfig.PvpMusicPool.HEAVY, c.playerCombatMusicPool);
	}

	@Test
	void clamp_pinsEveryRange() {
		BattleMusicConfig c = new BattleMusicConfig();
		c.detectionRadius = 500.0D;
		c.aggroMobCount = -3;
		c.aggroStickinessSeconds = 99.0D;
		c.fadeOutDurationSeconds = 0.0D;
		c.playerDamageWindowSeconds = 0.0D;
		c.regularFolderVolume = 5.0D;
		c.resumeAggroMobCount = 1000;
		c.clamp();
		assertEquals(128.0D, c.detectionRadius);
		assertEquals(1, c.aggroMobCount);
		assertEquals(30.0D, c.aggroStickinessSeconds);
		assertEquals(0.05D, c.fadeOutDurationSeconds);
		assertEquals(0.5D, c.playerDamageWindowSeconds);
		assertEquals(2.0D, c.regularFolderVolume);
		assertEquals(200, c.resumeAggroMobCount);
	}

	@Test
	void clamp_nanFallsBackToMinimum() {
		BattleMusicConfig c = new BattleMusicConfig();
		c.detectionRadius = Double.NaN;
		c.fadeInDurationSeconds = Double.NaN;
		c.heavyFolderVolume = Double.NaN;
		c.playerCombatTimeoutSeconds = Double.NaN;
		c.clamp();
		assertEquals(1.0D, c.detectionRadius);
		assertEquals(0.0D, c.fadeInDurationSeconds);
		assertEquals(0.0D, c.heavyFolderVolume);
		assertEquals(1.0D, c.playerCombatTimeoutSeconds);
	}

	@Test
	void clamp_repairsNullCollectionsAndPool() {
		BattleMusicConfig c = new BattleMusicConfig();
		c.extraBossIds = null;
		c.songSettings = null;
		c.playerCombatMusicPool = null;
		c.clamp();
		assertNotNull(c.extraBossIds);
		assertNotNull(c.songSettings);
		assertEquals(BattleMusicConfig.PvpMusicPool.HEAVY, c.playerCombatMusicPool);
	}

	@Test
	void clamp_boundsSongSettings() {
		BattleMusicConfig c = new BattleMusicConfig();
		BattleMusicConfig.SongSetting s = new BattleMusicConfig.SongSetting();
		s.volume = 9.0D;
		s.startSeconds = -4.0D;
		s.weight = 500.0D;
		c.songSettings.put("Regular Battle/x.ogg", s);
		c.clamp();
		assertEquals(2.0D, s.volume);
		assertEquals(0.0D, s.startSeconds);
		assertEquals(100.0D, s.weight);
	}

	@Test
	void saveLoad_roundTripPreservesValues() {
		BattleMusicConfig c = new BattleMusicConfig();
		c.detectionRadius = 33.0D;
		c.aggroMobCount = 7;
		c.enabled = false;
		c.debug = true;
		c.extraBossIds = new ArrayList<>(Arrays.asList("mydim:big"));
		BattleMusicConfig.SongSetting s = new BattleMusicConfig.SongSetting();
		s.volume = 1.5D;
		s.startSeconds = 12.5D;
		s.weight = 10.0D;
		c.songSettings.put("Heavy Battle/boss.ogg", s);
		c.save();

		BattleMusicConfig loaded = BattleMusicConfig.load();
		assertEquals(33.0D, loaded.detectionRadius);
		assertEquals(7, loaded.aggroMobCount);
		assertFalse(loaded.enabled);
		assertTrue(loaded.debug);
		assertEquals(Arrays.asList("mydim:big"), loaded.extraBossIds);
		BattleMusicConfig.SongSetting ls = loaded.songSettings.get("Heavy Battle/boss.ogg");
		assertNotNull(ls);
		assertEquals(1.5D, ls.volume);
		assertEquals(12.5D, ls.startSeconds);
		assertEquals(10.0D, ls.weight);
	}

	@Test
	void save_leavesNoTempFileBehind() throws Exception {
		new BattleMusicConfig().save();
		Path dir = temp.resolve("config");
		assertTrue(Files.exists(dir.resolve("battlemusic.json")));
		assertFalse(Files.exists(dir.resolve("battlemusic.json.tmp")));
	}

	@Test
	void loadMissingFile_createsDefaults() {
		BattleMusicConfig loaded = BattleMusicConfig.load();
		assertNotNull(loaded);
		assertEquals(25.0D, loaded.detectionRadius);
		assertTrue(Files.exists(temp.resolve("config").resolve("battlemusic.json")));
	}

	@Test
	void loadCorruptFile_fallsBackToDefaults() throws Exception {
		Path dir = temp.resolve("config");
		Files.createDirectories(dir);
		Files.write(dir.resolve("battlemusic.json"), "this is not json{{".getBytes(StandardCharsets.UTF_8));
		BattleMusicConfig loaded = BattleMusicConfig.load();
		assertNotNull(loaded);
		assertEquals(25.0D, loaded.detectionRadius);
	}
}
