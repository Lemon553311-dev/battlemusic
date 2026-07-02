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
 * Plain JSON config stored at config/battlemusic.json. Uses GSON (shipped with
 * Minecraft) so the mod has no hard dependency on Cloth Config; the optional
 * Cloth/ModMenu screen simply edits this same object and calls {@link #save()}.
 */

public class BattleMusicConfig {

	/** Which folder of tracks the PvP trigger uses when it STARTS a battle */
	public enum PvpMusicPool { HEAVY, REGULAR, BOTH }

    // Detect radius
	public double detectionRadius = 25.0D;
	/** Number of aggroed mobs required to start a regular battle */
	public int aggroMobCount = 5;
	/** Max angle (degrees) between a mob's head facing and the player to count as "looking at you" */
	public double headAimToleranceDegrees = 60.0D;
	/** Require an unobstructed line of sight from mob to player */
	public boolean requireLineOfSight = true;
	/** Seconds a mob stays "counted" after its last aggro signa */
	public double aggroStickinessSeconds = 1.5D;
	/** When true, a mob that is just standing around (stuck, out of reach, or only
	 * turning its head) stops sustaining battle music: the music keeps going only
	 * while mobs are actually moving toward you, circling, or attacking. Turn OFF for
	 * the old behavior, where any nearby hostile facing you keeps the music alive */
	public boolean requireActiveEngagement = true;
	/** When true, normally-neutral mobs (wolves, iron golems, etc.) that are actively
	 * attacking you count toward a battle too. Read from synced combat actions (melee
	 * swings / their projectiles), never from server-only AI targets, so a calm animal
	 * nearby never triggers it. Turn OFF to only ever count monster-type (Enemy) mobs */
	public boolean includeAttackingNeutrals = true;
	/** When true, a mob shooting projectiles at you (skeleton, drowned, etc.) counts as
	 * actively engaged even while standing still, so a perched archer keeps the music
	 * going. Only matters when {@link #requireActiveEngagement} is on */
	public boolean rangedAttacksCountAsEngagement = true;
	/** When true, only movement that actually closes distance (approaching) counts as
	 * "active"; a mob fidgeting or pacing at a steady distance (e.g. stuck below a tree
	 * you climbed) no longer sustains the music. Only matters when
	 * {@link #requireActiveEngagement} is on */
	public boolean engagementRequiresClosing = true;
	/** When true, the mob's vertical aim is also checked, so a mob that can only crane
	 * near-straight up or down at you (you're on a pillar or up a tree it cannot reach)
	 * does not count as "looking at you" from head yaw alone */
	public boolean headAimChecksPitch = true;

	// Heavy battle
	/** Player health (in HP, 2 HP = 1 heart) at or below which battle becomes heavy */
	public double heavyHealthThreshold = 6.0D;
	/** Aggroed-mob count that forces HEAVY battle on its own. When this many mobs (or more)
	 * are aggroed on you, the fight is heavy regardless of your health - a big swarm is always
	 * intense. Editable in the mod menu. Default 15. */
	public int heavyAggroMobCount = 15;
	/** Length (seconds) of the crossfade when a battle escalates from regular to heavy.
	 * The regular track fades down while the heavy track fades up over this same window,
	 * so they overlap instead of hard-cutting. 0 = instant switch (no crossfade) */
	public double heavyCrossfadeSeconds = 2.0D;

	// Player damage trigger
	/** When true, taking a burst of damage FROM ANOTHER PLAYER forces heavy battle */
	public boolean playerDamageTriggerEnabled = true;
	/** Damage (in HP; 2 HP = 1 heart) the player must RECEIVE from another player within the window to trigger heavy battle. Default 6 HP (= 3 hearts) */
	public double playerDamageThresholdHp = 6.0D;
	/** Rolling window (seconds) over which PvP damage received is summed for the trigger */
	public double playerDamageWindowSeconds = 5.0D;
	/** Heavy battle persists this long (seconds) after the last hit taken from a player. Refreshes on each player hit */
	public double playerCombatTimeoutSeconds = 15.0D;
	/** Which track pool the PvP trigger uses to start a battle: HEAVY (default), REGULAR, or BOTH. Mid-battle low-HP and bosses still always escalate to heavy */
	public PvpMusicPool playerCombatMusicPool = PvpMusicPool.HEAVY;

	// Bosses
	/** Radius (blocks) used for special boss detection. Bosses force heavy battle */
	public double bossRadius = 48.0D;
	/** Extra boss entity ids (e.g. "minecraft:elder_guardian") beyond the built-ins */
	public List<String> extraBossIds = new ArrayList<>();
	/** When true, sub-bosses (Elder Guardian, Ravager, Evoker, Piglin Brute) are treated
	 * like bosses: any one nearby forces heavy battle using the boss radius */
	public boolean includeMiniBosses = true;

	// Fades / timing
	/** Seconds of "no aggro" before the fade-out begins */
	public double fadeOutDelaySeconds = 15.0D;
	/** Length of the fade-out itself */
	public double fadeOutDurationSeconds = 7.0D;
	/** Length of the fade-in when a battle starts */
	public double fadeInDurationSeconds = 3.0D;

	// Battle resume
	/** When true, a new battle that starts within {@link #resumeWithinSeconds} of the
	 * last one continues the previous track where it faded out, instead of restarting it */
	public boolean battleResumeEnabled = true;
	/** If a new battle begins within this many seconds of the previous one ending, the
	 * track resumes from where it stopped. After this window, a fresh track plays from
	 * the start */
	public double resumeWithinSeconds = 30.0D;
	/** Minimum number of aggroed mobs needed to (re)start a battle while still inside the
	 * resume window (see {@link #resumeWithinSeconds}). Lets the "adrenaline" keep going with
	 * fewer mobs than a cold start. Independent of {@link #aggroMobCount}; only applies during
	 * the window */
	public int resumeAggroMobCount = 3;

	// ---- Output ----------------------------------------------------------
	/** Master on/off switch for the whole mod */
	public boolean enabled = true;
	/** Verbose debug logging to the game log/console (INFO level, prefixed with [DBG]) */
	public boolean debug = false;

	// ---- Secret "Fun" tab (password-gated) -------------------------------
	/** Unlocks the password-gated "Fun" tab. Entering the code only flips this;
	 * it never enables any individual feature - those stay off until the player
	 * turns them on. Persisted so the tab stays unlocked across restarts. */
	public boolean funUnlocked = false;
	/** Secret "Last Totem Standing" alert (sound + image flash on your last
	 * totem). Off by default even after the tab is unlocked. */
	public boolean lastTotemEnabled = false;
	/** Secret "Last Heart Standing" visual: flashes an image when a HEAVY battle
	 * starts specifically from the low-HP threshold (not PvP, bosses, or swarms).
	 * Off by default even after the tab is unlocked. */
	public boolean lastHeartEnabled = false;

	// ---- Per-folder / per-song music controls ----------------------------
	/** Volume multiplier for the whole Regular Battle folder (1.0 = 100% = unchanged). */
	public double regularFolderVolume = 1.0;
	/** Volume multiplier for the whole Heavy Battle folder (1.0 = 100% = unchanged). */
	public double heavyFolderVolume = 1.0;
	/** Per-song settings, keyed by "<folder>/<filename>" (e.g. "Heavy Battle/boss.ogg"). */
	public java.util.Map<String, SongSetting> songSettings = new java.util.HashMap<>();

	/** Per-song volume, start offset, and pick weight. Defaults leave a song unchanged. */
	public static class SongSetting {
		/** Volume multiplier, 0..2 (1.0 = 100% = unchanged, >1 boosts a quiet track). */
		public double volume = 1.0;
		/** Seconds into the track where playback starts on a fresh (non-resume) start. */
		public double startSeconds = 0.0;
		/** Relative pick weight 0..100 (all equal = equal chance; 0 = never plays). */
		public double weight = 50.0;
	}


	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Path path() {
		// Loader-neutral: FabricLoader on Fabric, FMLPaths on Forge/NeoForge.
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

	// Keep values inside sane ranges so bad edits cannot break playback
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