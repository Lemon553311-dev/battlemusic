package me.lemon553311.battlemusic.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import me.lemon553311.battlemusic.BattleMusicClient;
import me.lemon553311.battlemusic.lasttotem.LastTotemFeature;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import com.terraformersmc.modmenu.api.ModMenuApi;

import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.lemon553311.battlemusic.audio.MusicLibrary;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;

/**
 * ModMenu integration. Adds the "Config" button next to Battle Music in the
 * mods list and builds a Cloth Config settings screen for every option.
 *
 * This class is ONLY loaded when ModMenu is installed (it is referenced through
 * the "modmenu" entrypoint in fabric.mod.json), so the mod still runs fine
 * without ModMenu / Cloth Config present. The screen edits the same live
 * {@link BattleMusicConfig} instance the rest of the mod uses, then saves it to
 * disk and notifies the state machine so changes apply immediately.
 */

public class ModMenuIntegration implements ModMenuApi {

    @Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return ModMenuIntegration::buildScreen;
	}

	private static Screen buildScreen(Screen parent) {
		BattleMusicConfig cfg = BattleMusicClient.config();
		final BattleMusicConfig c = (cfg != null) ? cfg : new BattleMusicConfig();

		ConfigBuilder builder = ConfigBuilder.create()
				.setParentScreen(parent)
				.setTitle(Component.literal("Battle Music"));

		// Persist + apply live the moment the player clicks Save.
		builder.setSavingRunnable(() -> {
			c.save(); // also clamps values into safe ranges
			if (BattleMusicClient.stateMachine() != null) {
				BattleMusicClient.stateMachine().onConfigChanged();
			}
			BattleMusicClient.debug("Config saved from ModMenu screen: enabled={}, "
					+ "aggroMobCount={}, detectionRadius={}, heavyHealthThreshold={}, bossRadius={}",
					c.enabled, c.aggroMobCount, c.detectionRadius,
					c.heavyHealthThreshold, c.bossRadius);
		});

		ConfigEntryBuilder eb = builder.entryBuilder();

		// General
		ConfigCategory general = builder.getOrCreateCategory(Component.literal("General"));
		general.addEntry(eb.startBooleanToggle(Component.literal("Enabled"), c.enabled)
				.setDefaultValue(true)
				.setTooltip(Component.literal("Master on/off switch for the whole mod."))
				.setSaveConsumer(v -> c.enabled = v)
				.build());
		general.addEntry(eb.startBooleanToggle(Component.literal("Debug logging"), c.debug)
				.setDefaultValue(false)
				.setTooltip(Component.literal("Verbose [DBG] logging to the game log, for troubleshooting."))
				.setSaveConsumer(v -> c.debug = v)
				.build());

		// ---- Songs (second tab): per-folder + per-song volume, preview,
		// start-at, frequency, and the moved "open folder" shortcut. ----
		buildSongsCategory(builder, eb, c);

		// Detection
		ConfigCategory detection = builder.getOrCreateCategory(Component.literal("Detection"));
		detection.addEntry(eb.startIntSlider(Component.literal("Detection radius (blocks)"),
						(int) Math.round(c.detectionRadius), 1, 128)
				.setDefaultValue(25)
				.setTooltip(Component.literal("How far around you mobs are checked for aggro."))
				.setSaveConsumer(v -> c.detectionRadius = v)
				.build());
		detection.addEntry(eb.startIntSlider(Component.literal("Aggroed mobs to start battle"),
						c.aggroMobCount, 1, 50)
				.setDefaultValue(5)
				.setTooltip(Component.literal("How many mobs must be aggroed on you (within the radius) to start battle music."))
				.setSaveConsumer(v -> c.aggroMobCount = v)
				.build());
		detection.addEntry(eb.startIntSlider(Component.literal("Head-aim tolerance (degrees)"),
						(int) Math.round(c.headAimToleranceDegrees), 1, 180)
				.setDefaultValue(60)
				.setTooltip(Component.literal("How directly a mob must face you to count as aiming at you. Lower = stricter."))
				.setSaveConsumer(v -> c.headAimToleranceDegrees = v)
				.build());
		detection.addEntry(eb.startBooleanToggle(Component.literal("Require line of sight"), c.requireLineOfSight)
				.setDefaultValue(true)
				.setTooltip(Component.literal("Only count a mob if it has an unobstructed line of sight to you."))
				.setSaveConsumer(v -> c.requireLineOfSight = v)
				.build());
		detection.addEntry(eb.startDoubleField(Component.literal("Aggro stickiness (seconds)"), c.aggroStickinessSeconds)
				.setDefaultValue(1.5)
				.setMin(0.0).setMax(30.0)
				.setTooltip(Component.literal("How long a mob stays counted after its last aggro signal (anti-stutter)."))
				.setSaveConsumer(v -> c.aggroStickinessSeconds = v)
				.build());
		detection.addEntry(eb.startBooleanToggle(Component.literal("Require active engagement"), c.requireActiveEngagement)
				.setDefaultValue(true)
				.setTooltip(Component.literal("When on, mobs that just stand near you (stuck, out of reach, or only looking around) stop keeping the music going, it sustains only while mobs are actually approaching, circling, or attacking. Turn off for the old behavior, where any nearby hostile facing you keeps it alive."))
				.setSaveConsumer(v -> c.requireActiveEngagement = v)
				.build());
		detection.addEntry(eb.startBooleanToggle(Component.literal("Count attacking neutral mobs"), c.includeAttackingNeutrals)
				.setDefaultValue(true)
				.setTooltip(Component.literal("When on, normally-neutral mobs (wolves, iron golems, etc.) that are actively attacking you count toward a battle too. Detected from their actual attacks, so a calm animal nearby never triggers it."))
				.setSaveConsumer(v -> c.includeAttackingNeutrals = v)
				.build());
		detection.addEntry(eb.startBooleanToggle(Component.literal("Ranged attacks count as engagement"), c.rangedAttacksCountAsEngagement)
				.setDefaultValue(true)
				.setTooltip(Component.literal("When on, a mob shooting projectiles at you (skeleton, drowned, etc.) stays engaged even while standing still, so a perched archer keeps the music going. Only matters when Require active engagement is on."))
				.setSaveConsumer(v -> c.rangedAttacksCountAsEngagement = v)
				.build());
		detection.addEntry(eb.startBooleanToggle(Component.literal("Engagement requires approach"), c.engagementRequiresClosing)
				.setDefaultValue(true)
				.setTooltip(Component.literal("When on, only mobs genuinely closing in count as moving; a mob fidgeting or pacing at a steady distance (e.g. stuck below a tree you climbed) no longer keeps the music alive. Only matters when Require active engagement is on."))
				.setSaveConsumer(v -> c.engagementRequiresClosing = v)
				.build());
		detection.addEntry(eb.startBooleanToggle(Component.literal("Check vertical aim"), c.headAimChecksPitch)
				.setDefaultValue(true)
				.setTooltip(Component.literal("When on, a mob that can only crane near-straight up or down at you (you're on a pillar or up a tree it can't reach) is not counted as looking at you."))
				.setSaveConsumer(v -> c.headAimChecksPitch = v)
				.build());

		// Heavy battle
		ConfigCategory heavy = builder.getOrCreateCategory(Component.literal("Heavy Battle"));
		heavy.addEntry(eb.startIntSlider(Component.literal("Heavy HP threshold"),
						(int) Math.round(c.heavyHealthThreshold), 0, 40)
				.setDefaultValue(6)
				.setTextGetter(v -> Component.literal(v + " HP (" + (v / 2.0) + " hearts)"))
				.setTooltip(Component.literal("At or below this health, the music switches to heavy battle. 2 HP = 1 heart, so 6 HP = 3 hearts."))
				.setSaveConsumer(v -> c.heavyHealthThreshold = v)
				.build());
		heavy.addEntry(eb.startIntSlider(Component.literal("Aggroed mobs to force heavy"),
						c.heavyAggroMobCount, 1, 50)
				.setDefaultValue(15)
				.setTooltip(Component.literal("When this many mobs (or more) are aggroed on you, the fight is heavy no matter your health, a big swarm is always intense. Set above 'Aggroed mobs to start battle' to require a real horde."))
				.setSaveConsumer(v -> c.heavyAggroMobCount = v)
				.build());
		heavy.addEntry(eb.startDoubleField(Component.literal("Regular -> Heavy crossfade (seconds)"), c.heavyCrossfadeSeconds)
				.setDefaultValue(2.0).setMin(0.0).setMax(30.0)
				.setTooltip(Component.literal("How long the regular track and the heavy track overlap when a battle escalates to heavy. Both fade across this window for a smooth blend. 0 = instant switch (no crossfade)."))
				.setSaveConsumer(v -> c.heavyCrossfadeSeconds = v)
				.build());

		// PvP combat trigger
		ConfigCategory playerCombat = builder.getOrCreateCategory(Component.literal("PvP Combat"));
		playerCombat.addEntry(eb.startBooleanToggle(Component.literal("PvP trigger enabled"), c.playerDamageTriggerEnabled)
				.setDefaultValue(true)
				.setTooltip(Component.literal("When on, taking a burst of damage from ANOTHER PLAYER forces heavy battle (and can start one), even with no mobs around. Great for PvP."))
				.setSaveConsumer(v -> c.playerDamageTriggerEnabled = v)
				.build());
		playerCombat.addEntry(eb.startIntSlider(Component.literal("PvP damage to trigger heavy"),
						(int) Math.round(c.playerDamageThresholdHp), 1, 40)
				.setDefaultValue(6)
				.setTextGetter(v -> Component.literal(v + " HP (" + (v / 2.0) + " hearts)"))
				.setTooltip(Component.literal("How much damage you must take from another player within the window to force heavy battle. 2 HP = 1 heart, so 6 HP = 3 hearts."))
				.setSaveConsumer(v -> c.playerDamageThresholdHp = v)
				.build());
		playerCombat.addEntry(eb.startDoubleField(Component.literal("PvP damage window (seconds)"), c.playerDamageWindowSeconds)
				.setDefaultValue(5.0).setMin(0.5).setMax(60.0)
				.setTooltip(Component.literal("Rolling window over which PvP damage you take is summed toward the trigger."))
				.setSaveConsumer(v -> c.playerDamageWindowSeconds = v)
				.build());
		playerCombat.addEntry(eb.startDoubleField(Component.literal("Combat timeout (seconds)"), c.playerCombatTimeoutSeconds)
				.setDefaultValue(10.0).setMin(1.0).setMax(600.0)
				.setTooltip(Component.literal("How long PvP music keeps playing after the last hit from another player. It refreshes on every hit, so an active fight never cuts out, and the music ends this many calm seconds after the fight. This is the MAIN control for how long PvP music lingers, lower it for a snappier stop."))
				.setSaveConsumer(v -> c.playerCombatTimeoutSeconds = v)
				.build());
		playerCombat.addEntry(eb.startEnumSelector(Component.literal("PvP music pool"),
						BattleMusicConfig.PvpMusicPool.class,
						c.playerCombatMusicPool == null ? BattleMusicConfig.PvpMusicPool.HEAVY : c.playerCombatMusicPool)
				.setDefaultValue(BattleMusicConfig.PvpMusicPool.HEAVY)
				.setEnumNameProvider(v -> {
					BattleMusicConfig.PvpMusicPool p = (BattleMusicConfig.PvpMusicPool) v;
					return switch (p) {
						case REGULAR -> Component.literal("Regular Battle pool");
						case BOTH -> Component.literal("Regular + Heavy pool");
						case HEAVY -> Component.literal("Heavy Battle pool");
					};
				})
				.setTooltip(Component.literal("Which folder of tracks plays when the PvP trigger STARTS a battle. Heavy = Heavy Battle folder (default, most intense). Regular = Regular Battle folder (calmer). Both = randomly from either folder. Low-HP and bosses still always escalate to heavy."))
				.setSaveConsumer(v -> c.playerCombatMusicPool = v)
				.build());

		// Bosses
		ConfigCategory boss = builder.getOrCreateCategory(Component.literal("Bosses"));
		boss.addEntry(eb.startIntSlider(Component.literal("Boss detection radius (blocks)"),
						(int) Math.round(c.bossRadius), 1, 256)
				.setDefaultValue(48)
				.setTooltip(Component.literal("Detection radius used for bosses (Warden, Ender Dragon, Wither, etc.). Bosses force heavy battle."))
				.setSaveConsumer(v -> c.bossRadius = v)
				.build());
		boss.addEntry(eb.startStrList(Component.literal("Extra boss entity IDs"), new ArrayList<>(c.extraBossIds))
				.setDefaultValue(new ArrayList<>())
				.setTooltip(Component.literal("Extra boss entity ids beyond the built-ins, e.g. minecraft:elder_guardian."))
				.setSaveConsumer(v -> c.extraBossIds = new ArrayList<>(v))
				.build());
		boss.addEntry(eb.startBooleanToggle(Component.literal("Treat sub-bosses as bosses"), c.includeMiniBosses)
				.setDefaultValue(true)
				.setTooltip(Component.literal("When on, Elder Guardian, Ravager, Evoker and Piglin Brute count as bosses: any one nearby forces heavy battle using the boss radius."))
				.setSaveConsumer(v -> c.includeMiniBosses = v)
				.build());

		// Fades & timing
		ConfigCategory fades = builder.getOrCreateCategory(Component.literal("Fades & Timing"));
		fades.addEntry(eb.startDoubleField(Component.literal("Fade-out delay (seconds)"), c.fadeOutDelaySeconds)
				.setDefaultValue(15.0).setMin(0.0).setMax(600.0)
				.setTooltip(Component.literal("How long after the last aggro leaves before the music begins fading out."))
				.setSaveConsumer(v -> c.fadeOutDelaySeconds = v)
				.build());
		fades.addEntry(eb.startDoubleField(Component.literal("Fade-out duration (seconds)"), c.fadeOutDurationSeconds)
				.setDefaultValue(7.0).setMin(0.05).setMax(60.0)
				.setTooltip(Component.literal("Length of the fade-out itself."))
				.setSaveConsumer(v -> c.fadeOutDurationSeconds = v)
				.build());
		fades.addEntry(eb.startDoubleField(Component.literal("Fade-in duration (seconds)"), c.fadeInDurationSeconds)
				.setDefaultValue(3.0).setMin(0.0).setMax(60.0)
				.setTooltip(Component.literal("Length of the fade-in when a battle starts."))
				.setSaveConsumer(v -> c.fadeInDurationSeconds = v)
				.build());

		// Battle resume ("continue the heat")
		ConfigCategory resume = builder.getOrCreateCategory(Component.literal("Battle Resume"));
		resume.addEntry(eb.startBooleanToggle(Component.literal("Resume enabled"), c.battleResumeEnabled)
				.setDefaultValue(true)
				.setTooltip(Component.literal("When on, a battle that starts soon after the last one continues the track where it faded out, instead of restarting from the beginning."))
				.setSaveConsumer(v -> c.battleResumeEnabled = v)
				.build());
		resume.addEntry(eb.startDoubleField(Component.literal("Resume window (seconds)"), c.resumeWithinSeconds)
				.setDefaultValue(30.0).setMin(0.0).setMax(600.0)
				.setTooltip(Component.literal("If a new battle begins within this many seconds of the previous one ending, the track continues where it left off. After this window, a fresh track plays from the start."))
				.setSaveConsumer(v -> c.resumeWithinSeconds = v)
				.build());
		resume.addEntry(eb.startIntSlider(Component.literal("Resume-window mobs to continue"),
						c.resumeAggroMobCount, 1, 50)
				.setDefaultValue(3)
				.setTooltip(Component.literal("While inside the resume window, this many aggroed mobs is enough to re-start a battle, instead of the normal 'Aggroed mobs to start battle'. Lets the adrenaline keep going with fewer mobs right after a fight."))
				.setSaveConsumer(v -> c.resumeAggroMobCount = v)
				.build());

		// Password-gated "Fun" tab. The tab is ALWAYS just called "Fun" in both
		// states, so it gives nothing away. Entering the code only UNLOCKS the tab
		// (funUnlocked); it never turns any feature on, so everything inside stays
		// off until the player flips it. (lastTotemEnabled is still honored as
		// "unlocked" so configs unlocked before this split don't get re-locked.)
		boolean funUnlocked = c.funUnlocked || c.lastTotemEnabled;
		ConfigCategory secret = builder.getOrCreateCategory(Component.literal("Fun"));
		if (!funUnlocked) {
			// Locked: give away nothing about what this is or what it does.
			secret.addEntry(eb.startTextDescription(
					Component.literal("Got a code? Enter it below and click Save.")
							.withStyle(s -> s.withColor(ChatFormatting.GRAY)))
					.build());
			secret.addEntry(eb.startStrField(Component.literal("Code"), "")
					.setDefaultValue("")
					.setTooltip(Component.literal("Enter a code and click Save."))
					.setSaveConsumer(v -> {
						if (v != null && v.trim().equalsIgnoreCase(LastTotemFeature.PASSWORD)) {
							// Only unlock the tab. Do NOT enable any feature by default.
							c.funUnlocked = true;
						}
					})
					.build());
		} else {
			// Unlocked: reveal the features. Each stays OFF until toggled on.
			secret.addEntry(eb.startTextDescription(
					Component.literal("Secret extras. Everything here is off by default \u2014 flip on what you want.")
							.withStyle(s -> s.withColor(ChatFormatting.GRAY)))
					.build());

			// Last Totem Standing: sound + image flash on your last totem.
			secret.addEntry(eb.startTextDescription(
					Component.literal("Last Totem Standing \u2014 plays a sound and flashes an image when you drop to your last totem.")
							.withStyle(s -> s.withColor(ChatFormatting.GRAY)))
					.build());
			secret.addEntry(eb.startBooleanToggle(Component.literal("Last Totem Standing"), c.lastTotemEnabled)
					.setDefaultValue(false)
					.setTooltip(Component.literal("Turn the Last Totem Standing alert on or off."))
					.setSaveConsumer(v -> c.lastTotemEnabled = v)
					.build());

			// Last Heart Standing: image flash when low HP forces a heavy battle.
			secret.addEntry(eb.startTextDescription(
					Component.literal("Last Heart Standing \u2014 flashes an image when a heavy battle starts because your health dropped to the heavy HP threshold (not from PvP, bosses, or swarms).")
							.withStyle(s -> s.withColor(ChatFormatting.GRAY)))
					.build());
			secret.addEntry(eb.startBooleanToggle(Component.literal("Last Heart Standing"), c.lastHeartEnabled)
					.setDefaultValue(false)
					.setTooltip(Component.literal("Flash an image when low health forces a heavy battle. PvP-triggered heavy never shows it."))
					.setSaveConsumer(v -> c.lastHeartEnabled = v)
					.build());
		}

		return builder.build();
	}

	// ===== Songs tab =====================================================

	private static void buildSongsCategory(ConfigBuilder builder, ConfigEntryBuilder eb, BattleMusicConfig c) {
		ConfigCategory songs = builder.getOrCreateCategory(Component.literal("Songs"));
		MusicLibrary lib = BattleMusicClient.library();
		if (lib == null) {
			songs.addEntry(eb.startTextDescription(
					Component.literal("Music library unavailable.").withStyle(s -> s.withColor(ChatFormatting.GRAY)))
					.build());
			return;
		}

		// Refresh so newly added files show up the moment the screen opens.
		lib.rescan();

		songs.addEntry(eb.startTextDescription(Component.literal(
				"Per-song and per-folder controls for every track. Volumes are %, "
				+ "100% = unchanged. Frequency sets how often a track is picked relative to the others in "
				+ "its folder. Click Save to apply.")
				.withStyle(s -> s.withColor(ChatFormatting.GRAY)))
				.build());

		// Where to drop your .ogg files. Shown as plain text so there is no
		// version-specific clickable widget to maintain.
		songs.addEntry(eb.startTextDescription(
				Component.literal("\uD83D\uDCC1 Music folder: " + lib.getRootFolder().toAbsolutePath())
						.withStyle(s -> s.withColor(ChatFormatting.GRAY)))
				.build());

		// Per-folder volume.
		songs.addEntry(eb.startIntSlider(Component.literal("Regular Battle folder volume"),
						(int) Math.round(c.regularFolderVolume * 100), 0, 200)
				.setDefaultValue(100)
				.setTextGetter(v -> Component.literal(v + "%"))
				.setTooltip(Component.literal("Volume for the whole Regular Battle folder. Multiplies with each song's own volume."))
				.setSaveConsumer(v -> c.regularFolderVolume = v / 100.0)
				.build());
		songs.addEntry(eb.startIntSlider(Component.literal("Heavy Battle folder volume"),
						(int) Math.round(c.heavyFolderVolume * 100), 0, 200)
				.setDefaultValue(100)
				.setTextGetter(v -> Component.literal(v + "%"))
				.setTooltip(Component.literal("Volume for the whole Heavy Battle folder. Multiplies with each song's own volume."))
				.setSaveConsumer(v -> c.heavyFolderVolume = v / 100.0)
				.build());

		addFolderSongs(songs, eb, c, lib, "Regular Battle", lib.regularTracks());
		addFolderSongs(songs, eb, c, lib, "Heavy Battle", lib.heavyTracks());
	}

	private static void addFolderSongs(ConfigCategory songs, ConfigEntryBuilder eb, BattleMusicConfig c,
			MusicLibrary lib, String folderLabel, List<Path> tracks) {
		songs.addEntry(eb.startTextDescription(
				Component.literal("\u2500\u2500 " + folderLabel + " (" + tracks.size() + ") \u2500\u2500")
						.withStyle(s -> s.withColor(ChatFormatting.GOLD)))
				.build());

		if (tracks.isEmpty()) {
			songs.addEntry(eb.startTextDescription(
					Component.literal("No .ogg files in this folder yet.").withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY)))
					.build());
			return;
		}

		// Total saved weight in this folder, for the live (approximate) % readout.
		double folderTotal = 0.0;
		for (Path p : tracks) folderTotal += savedWeight(c, lib.keyFor(p));
		final double folderTotalAtOpen = folderTotal;

		for (Path path : tracks) {

			final String key = lib.keyFor(path);
			final String fileName = path.getFileName().toString();
			final double savedVol = savedVolume(c, key);
			final double savedStartSec = savedStart(c, key);
			final double savedW = savedWeight(c, key);

			List<AbstractConfigListEntry> kids = new ArrayList<>();


			// Per-song volume.
			kids.add(eb.startIntSlider(Component.literal("Volume"),
							(int) Math.round(savedVol * 100), 0, 200)
					.setDefaultValue(100)
					.setTextGetter(v -> Component.literal(v + "%"))
					.setTooltip(Component.literal("This track's volume. Multiplies with the folder volume. 100% = unchanged, >100% boosts a quiet track."))
					.setSaveConsumer(v -> setting(c, key).volume = v / 100.0)
					.build());

			// Start at (seconds).
			kids.add(eb.startDoubleField(Component.literal("Start at (seconds)"), savedStartSec)
					.setDefaultValue(0.0).setMin(0.0).setMax(100000.0)
					.setTooltip(Component.literal("Seconds into the track where playback begins on a fresh start. 0 = from the beginning. (A battle resume still continues where it left off.)"))
					.setSaveConsumer(v -> setting(c, key).startSeconds = v)
					.build());

			// Frequency / pick weight, with a live normalized % readout.
			kids.add(eb.startIntSlider(Component.literal("Frequency"),
							(int) Math.round(savedW), 0, 100)
					.setDefaultValue(50)
					.setTextGetter(v -> {
						if (v <= 0) return Component.literal("never");
						double denom = folderTotalAtOpen - savedW + v;
						double pct = denom > 0 ? (v * 100.0 / denom) : 0.0;
						return Component.literal(String.format("\u2248%.0f%% of this folder", pct));
					})
					.setTooltip(Component.literal("How often this track is picked relative to the others in its folder. Raising it lowers everyone else's share; 0 = never plays. The % is approximate until you Save."))
					.setSaveConsumer(v -> setting(c, key).weight = v)
					.build());

			songs.addEntry(eb.startSubCategory(Component.literal("\u266A " + fileName), kids)
					.setExpanded(false)
					.build());
		}
	}

	private static BattleMusicConfig.SongSetting setting(BattleMusicConfig c, String key) {
		if (c.songSettings == null) c.songSettings = new java.util.HashMap<>();
		return c.songSettings.computeIfAbsent(key, k -> new BattleMusicConfig.SongSetting());
	}
	private static double savedVolume(BattleMusicConfig c, String key) {
		BattleMusicConfig.SongSetting s = (c.songSettings == null) ? null : c.songSettings.get(key);
		return s != null ? s.volume : 1.0;
	}
	private static double savedStart(BattleMusicConfig c, String key) {
		BattleMusicConfig.SongSetting s = (c.songSettings == null) ? null : c.songSettings.get(key);
		return s != null ? s.startSeconds : 0.0;
	}
	private static double savedWeight(BattleMusicConfig c, String key) {
		BattleMusicConfig.SongSetting s = (c.songSettings == null) ? null : c.songSettings.get(key);
		return s != null ? s.weight : 50.0;
	}
}