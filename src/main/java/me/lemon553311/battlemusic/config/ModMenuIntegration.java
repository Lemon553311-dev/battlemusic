package me.lemon553311.battlemusic.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import me.lemon553311.battlemusic.BattleMusicClient;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import com.terraformersmc.modmenu.api.ModMenuApi;

import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.ChatFormatting;

import java.util.ArrayList;

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

		// ---- General --------------------------------------------------------
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

			// A clickable shortcut that opens the battle music folder in the OS file browser.
		var lib = BattleMusicClient.library();
		if (lib != null) {
			general.addEntry(eb.startTextDescription(
					Component.literal("Open the battle music folder")
							.withStyle(s -> s
									.withColor(ChatFormatting.AQUA)
									.withUnderlined(true)
									.withClickEvent(new ClickEvent.OpenFile(lib.getRootFolder()))))
					.build());
		}

		// ---- Detection ------------------------------------------------------
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
				.setTooltip(Component.literal("When on, mobs that just stand near you (stuck, out of reach, or only looking around) stop keeping the music going \u2014 it sustains only while mobs are actually approaching, circling, or attacking. Turn off for the old behavior, where any nearby hostile facing you keeps it alive."))
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

		// ---- Heavy battle ---------------------------------------------------
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
				.setTooltip(Component.literal("When this many mobs (or more) are aggroed on you, the fight is heavy no matter your health \u2014 a big swarm is always intense. Set above 'Aggroed mobs to start battle' to require a real horde."))
				.setSaveConsumer(v -> c.heavyAggroMobCount = v)
				.build());
		heavy.addEntry(eb.startDoubleField(Component.literal("Regular -> Heavy crossfade (seconds)"), c.heavyCrossfadeSeconds)
				.setDefaultValue(2.0).setMin(0.0).setMax(30.0)
				.setTooltip(Component.literal("How long the regular track and the heavy track overlap when a battle escalates to heavy. Both fade across this window for a smooth blend. 0 = instant switch (no crossfade)."))
				.setSaveConsumer(v -> c.heavyCrossfadeSeconds = v)
				.build());

		// ---- PvP combat trigger --------------------------------------------
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
				.setTooltip(Component.literal("How long PvP music keeps playing after the last hit from another player. It refreshes on every hit, so an active fight never cuts out, and the music ends this many calm seconds after the fight. This is the MAIN control for how long PvP music lingers \u2014 lower it for a snappier stop."))
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

		// ---- Bosses ---------------------------------------------------------
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

		// ---- Fades & timing -------------------------------------------------
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

		// ---- Battle resume ("continue the heat") ---------------------------
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

		return builder.build();
	}
}