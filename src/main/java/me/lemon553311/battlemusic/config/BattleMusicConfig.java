package me.lemon553311.battlemusic.config;

import me.lemon553311.battlemusic.BattleMusicClient;
import me.lemon553311.battlemusic.platform.Platform;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain JSON config at config/battlemusic.json, GSON only (no hard Cloth
 * dependency); the optional screen edits this same object and saves it.
 */

public class BattleMusicConfig {

	public enum PvpMusicPool { HEAVY, REGULAR, BOTH }

    // Detect radius
	public double detectionRadius = 25.0D;
	/** Aggroed mobs required to start a regular battle */
	public int aggroMobCount = 5;
	/** Max angle (degrees) between a mob's head facing and the player to count as "looking at you" */
	public double headAimToleranceDegrees = 60.0D;
	public boolean requireLineOfSight = true;
	/** How long a mob stays counted after its last aggro signal */
	public double aggroStickinessSeconds = 1.5D;
	/** When on, mobs must actually be moving in / attacking to sustain the music;
	 * just standing there facing you isn't enough */
	public boolean requireActiveEngagement = true;
	/** When on, normally-neutral mobs (wolves, golems, ...) attacking you count too */
	public boolean includeAttackingNeutrals = true;
	/** A mob shooting at you stays engaged even while standing still (perched archers) */
	public boolean rangedAttacksCountAsEngagement = true;
	/** Only movement that actually closes distance counts as active */
	public boolean engagementRequiresClosing = true;
	/** Also check vertical aim, so a mob craning straight up/down at you doesn't count */
	public boolean headAimChecksPitch = true;

	// Heavy battle
	/** Health (HP) at or below which battle becomes heavy */
	public double heavyHealthThreshold = 6.0D;
	/** This many aggroed mobs force heavy on their own - a big swarm is always intense */
	public int heavyAggroMobCount = 15;
	/** Crossfade length when escalating regular -> heavy; 0 = instant switch */
	public double heavyCrossfadeSeconds = 2.0D;

	// Player damage trigger
	public boolean playerDamageTriggerEnabled = true;
	/** Damage (HP) to receive from another player within the window to trigger */
	public double playerDamageThresholdHp = 6.0D;
	/** Rolling window over which pvp damage is summed */
	public double playerDamageWindowSeconds = 5.0D;
	/** Heavy persists this long after the last player hit; refreshes on every hit */
	public double playerCombatTimeoutSeconds = 15.0D;
	/** Which pool the pvp trigger uses when it STARTS a battle */
	public PvpMusicPool playerCombatMusicPool = PvpMusicPool.HEAVY;

	// Bosses
	public double bossRadius = 48.0D;
	/** Extra boss entity ids beyond the built-ins, e.g. "minecraft:elder_guardian" */
	public List<String> extraBossIds = new ArrayList<>();
	/** Elder Guardian / Ravager / Evoker / Piglin Brute count as bosses */
	public boolean includeMiniBosses = true;

	// Fades / timing
	/** Seconds of "no aggro" before the fade-out begins */
	public double fadeOutDelaySeconds = 15.0D;
	public double fadeOutDurationSeconds = 7.0D;
	public double fadeInDurationSeconds = 3.0D;

	// Battle resume
	/** A new battle within resumeWithinSeconds continues the previous track */
	public boolean battleResumeEnabled = true;
	public double resumeWithinSeconds = 30.0D;
	/** Mobs needed to re-start inside the resume window ("adrenaline") */
	public int resumeAggroMobCount = 3;

	// ---- Output ----------------------------------------------------------
	public boolean enabled = true;
	/** Verbose [DBG] logging */
	public boolean debug = false;

	// ---- Secret "Fun" tab (password-gated) -------------------------------
	/** Unlocks the tab; never enables anything by itself */
	public boolean funUnlocked = false;
	/** Sound + image flash on your last totem */
	public boolean lastTotemEnabled = false;
	/** Image flash when low HP alone forces heavy */
	public boolean lastHeartEnabled = false;

	// ---- Per-folder / per-song music controls ----------------------------
	public double regularFolderVolume = 1.0;
	public double heavyFolderVolume = 1.0;
	/** keyed by "<folder>/<filename>" */
	public java.util.Map<String, SongSetting> songSettings = new java.util.HashMap<>();

	public static class SongSetting {
		/** volume multiplier, >1 boosts a quiet track */
		public double volume = 1.0;
		/** seconds in where playback starts on a fresh start */
		public double startSeconds = 0.0;
		/** relative pick weight, 0 = never plays */
		public double weight = 50.0;
	}


	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Path path() {
		return Platform.configDir().resolve("battlemusic.json");
	}

	@SuppressWarnings("null")
	public static BattleMusicConfig load() {
		Path p = path();

		try {
			if (Files.exists(p)) {
				String json = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
				BattleMusicConfig cfg = GSON.fromJson(json, BattleMusicConfig.class);

				if (cfg != null) {
					cfg.clamp();
					return cfg;
				}
			}

		} catch (Exception e) {
			BattleMusicClient.LOGGER.warn("Failed to read config, using defaults", e);
		}

		BattleMusicConfig cfg = new BattleMusicConfig();
		cfg.save();
		return cfg;
	}

	public void save() {
		clamp();

		try {
			Files.createDirectories(path().getParent());
			Files.write(path(), GSON.toJson(this).getBytes(StandardCharsets.UTF_8));

		} catch (IOException e) {
			BattleMusicClient.LOGGER.warn("Failed to save config", e);
		}
	}

	// keep values in sane ranges so bad edits can't break playback
	public void clamp() {
		detectionRadius = clampD(detectionRadius, 1.0D, 128.0D);
		aggroMobCount = (int) clampD(aggroMobCount, 1, 200);
		headAimToleranceDegrees = clampD(headAimToleranceDegrees, 1.0D, 180.0D);
		aggroStickinessSeconds = clampD(aggroStickinessSeconds, 0.0D, 30.0D);
		heavyHealthThreshold = clampD(heavyHealthThreshold, 0.0D, 1024.0D);
		heavyAggroMobCount = (int) clampD(heavyAggroMobCount, 1, 200);
		heavyCrossfadeSeconds = clampD(heavyCrossfadeSeconds, 0.0D, 30.0D);
		bossRadius = clampD(bossRadius, 1.0D, 256.0D);
		playerDamageThresholdHp = clampD(playerDamageThresholdHp, 1.0D, 200.0D);
		playerDamageWindowSeconds = clampD(playerDamageWindowSeconds, 0.5D, 60.0D);
		playerCombatTimeoutSeconds = clampD(playerCombatTimeoutSeconds, 1.0D, 600.0D);
		fadeOutDelaySeconds = clampD(fadeOutDelaySeconds, 0.0D, 600.0D);
		fadeOutDurationSeconds = clampD(fadeOutDurationSeconds, 0.05D, 60.0D);
		fadeInDurationSeconds = clampD(fadeInDurationSeconds, 0.0D, 60.0D);
		resumeWithinSeconds = clampD(resumeWithinSeconds, 0.0D, 600.0D);
		resumeAggroMobCount = (int) clampD(resumeAggroMobCount, 1, 200);
		if (extraBossIds == null) extraBossIds = new ArrayList<>();
		if (playerCombatMusicPool == null) playerCombatMusicPool = PvpMusicPool.HEAVY;
		regularFolderVolume = clampD(regularFolderVolume, 0.0, 2.0);
		heavyFolderVolume = clampD(heavyFolderVolume, 0.0, 2.0);
		if (songSettings == null) songSettings = new java.util.HashMap<>();
		for (SongSetting s : songSettings.values()) {
			if (s == null) continue;
			s.volume = clampD(s.volume, 0.0, 2.0);
			s.startSeconds = clampD(s.startSeconds, 0.0, 100000.0);
			s.weight = clampD(s.weight, 0.0, 100.0);
		}
	}

	private static double clampD(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}