package me.lemon553311.battlemusic.state;

import me.lemon553311.battlemusic.BattleMusicClient;
import me.lemon553311.battlemusic.audio.AudioEngine;
import me.lemon553311.battlemusic.audio.MusicChannel;
import me.lemon553311.battlemusic.audio.MusicLibrary;
import me.lemon553311.battlemusic.config.BattleMusicConfig;
import me.lemon553311.battlemusic.detection.AggroTracker;
import me.lemon553311.battlemusic.detection.BossDetector;
import me.lemon553311.battlemusic.detection.PlayerDamageTracker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundSource;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 *
 * @author Lemon553311
 * @author uxokpro1234
 * @author user2378
 * 
 * it actually gets worse! 
 * 
 */

public class BattleStateMachine {
	private enum Phase { IDLE, REGULAR, HEAVY }

	private final BattleMusicConfig config;
	private final AudioEngine engine;
	private final MusicLibrary library;
	private final AggroTracker aggro;
	private final BossDetector bosses;
	private final PlayerDamageTracker damage;

	private final MusicChannel regularChannel;
	private final MusicChannel heavyChannel;

	private Phase phase = Phase.IDLE;
	private boolean battleActive = false;
	private boolean heavyLatched = false;
	private double graceSecondsLeft = 0.0;
	private boolean battleHeldByMobsOrBoss = false;
	private double playerCombatSecondsLeft = 0.0;
	private boolean playerCombatWasHot = false;
	// keep music on death screen and no more after respawn
	private boolean playerWasDead = false;
	private static final double DEATH_HOLD_MAX_SECONDS = 15.0;
	private double deathHoldSecondsLeft = 0.0;
	// battle started from the pvp trigger with pool=BOTH: re-rolls in REGULAR phase keep
	// picking from both folders.
	private boolean regularUsesBothPool = false;
	// track music for pvp n shit
	private boolean pvpPoolBattle = false;

	// resume battle
	private Path resumeRegularFile = null;
	private long resumeRegularFrame = 0L;
	private Path resumeHeavyFile = null;
	private long resumeHeavyFrame = 0L;
	private long resumeStampNanos = 0L;

	private long lastTickNanos = 0L;
	private double debugAccum = 0.0;
	// count ticks independently cuz time can stop apparently on servers
	// why is this even a thing
	private long clientTick = 0L;

	public BattleStateMachine(BattleMusicConfig config, AudioEngine engine, MusicLibrary library) {
		this.config = config;
		this.engine = engine;
		this.library = library;
		this.aggro = new AggroTracker(config);
		this.bosses = new BossDetector(config);
		this.damage = new PlayerDamageTracker(config);
		this.regularChannel = new MusicChannel(engine, "regular");
		this.heavyChannel = new MusicChannel(engine, "heavy");
	}

	public void onClientTick(Minecraft client) {
		double dt = computeDeltaSeconds();

		if (!engine.isReady()) return;

		float mcVolume = client.options.getSoundSourceVolume(SoundSource.MASTER)
				* client.options.getSoundSourceVolume(SoundSource.MUSIC);
		regularChannel.setOutputVolume(mcVolume);
		heavyChannel.setOutputVolume(mcVolume);

		LocalPlayer player = client.player;
		ClientLevel world = client.level;

		if (!config.enabled || player == null || world == null || client.isPaused()) {
			// Animate fades down but do not evaluate detection.
			if (battleActive) beginFadeOut(true);
			playerCombatSecondsLeft = 0.0;
			playerCombatWasHot = false;
			damage.clear();
			tickChannels(dt);
			return;
		}


		if (player.isDeadOrDying()) {
			if (!playerWasDead) {
				playerWasDead = true;
				deathHoldSecondsLeft = DEATH_HOLD_MAX_SECONDS;
			}
			// handle fade out if player stay on death screen for some reason
			if (deathHoldSecondsLeft > 0.0) {
				deathHoldSecondsLeft -= dt;
				if (deathHoldSecondsLeft <= 0.0 && battleActive) {
					beginFadeOut(false);
				}
			}
			tickChannels(dt);
			return;
		}
		if (playerWasDead) {
			//stop music kthx
			playerWasDead = false;
			stopForRespawn();
			tickChannels(dt);
			return;
		}

		// Advance our own tick clock once per evaluated tick (see field doc).
		clientTick++;

		aggro.update(player, world, clientTick);
		int count = aggro.getAggroCount();
		boolean boss = bosses.anyBossNearby(player, world, clientTick);

		// pvp trigger: a burst of damage from another player forces heavy and can start a
		// battle on its own. the combat-heat timer keeps it going through a duel, refreshed
		// on every player hit so a long fight never cuts out but idling fades.
		// only player damage re-arms it. fall/lava/fire after a duel must NOT keep pvp music
		// alive with nobody around.
		damage.update(player, world, clientTick);
		boolean combatActivity = damage.receivedThisTick();
		// combat-heat timer = time since the last pvp hit, so it holds through a duel and
		// only fades once contact stops for playerCombatTimeoutSeconds.
		//   - a qualifying burst (>= threshold in the rolling window) arms it.
		//   - after that ANY player hit refreshes it, even small ones below the burst.
		if (damage.isTriggered()) {
			playerCombatSecondsLeft = config.playerCombatTimeoutSeconds; // arm on a qualifying burst
		} else if (combatActivity && playerCombatSecondsLeft > 0.0) {
			playerCombatSecondsLeft = config.playerCombatTimeoutSeconds; // keep hot on any later player hit
		}
		playerCombatSecondsLeft = Math.max(0.0, playerCombatSecondsLeft - dt);
		boolean playerCombatHot = playerCombatSecondsLeft > 0.0;
		if (playerCombatHot && !playerCombatWasHot) {
			BattleMusicClient.debug("PVP TRIGGER: took {} HP from another player within {}s -> forcing HEAVY (combat timeout {}s)",
					String.format(java.util.Locale.ROOT, "%.1f", damage.getRecentDamageHp()),
					config.playerDamageWindowSeconds, config.playerCombatTimeoutSeconds);
		}
		playerCombatWasHot = playerCombatHot;

		// While still inside the post-battle resume window, the bar to RE-start a battle drops
		// to resumeAggroMobCount (the "adrenaline keeps going" trigger). Cold starts outside the
		// window still require the full aggroMobCount.
		int mobThreshold = (!battleActive && inResumeWindow()) ? config.resumeAggroMobCount : config.aggroMobCount;
		boolean qualifies = count >= mobThreshold || boss || playerCombatHot;

		logStatusThrottled(dt, player, count, boss, qualifies, playerCombatHot);

		if (!battleActive) {
			if (qualifies) {
				if (mobThreshold < config.aggroMobCount && count >= mobThreshold
						&& count < config.aggroMobCount && !boss && !playerCombatHot) {
					BattleMusicClient.debug("RESUME-WINDOW RESTART: {} mob(s) >= resumeAggroMobCount {} (cold start needs {}) within {}s window",
							count, config.resumeAggroMobCount, config.aggroMobCount, config.resumeWithinSeconds);
				}
				startBattle(player, boss, playerCombatHot, count);
			}
		} else {
			boolean stillFighting = count > 0 || boss || playerCombatHot;
			if (stillFighting) {
				graceSecondsLeft = 0.0;
				cancelFadeOutIfNeeded();
				maybeUpgradeToHeavy(player, boss, playerCombatHot, count);
				// track what's actually holding the battle. mobs/boss can re-aggro so they keep
				// the grace window when they clear; a pvp-timer-only battle doesn't need it (the
				// timeout already buffered the gap).
				battleHeldByMobsOrBoss = count > 0 || boss;
			} else if (battleHeldByMobsOrBoss) {
				// A mob/boss battle just emptied out: keep the re-aggro grace window before fading.
				if (graceSecondsLeft <= 0.0 && !isFadingOut()) {
					graceSecondsLeft = config.fadeOutDelaySeconds;
					BattleMusicClient.debug("No aggro left; starting {}s grace timer before fade-out", config.fadeOutDelaySeconds);
				}
				graceSecondsLeft -= dt;
				if (graceSecondsLeft <= 0.0) {
					beginFadeOut(false);
				}
			} else {
				// pvp-only battle and the combat timer went cold. nothing to re-aggro, so fade
				// now instead of stacking grace on top of the combat timeout. the fade-out
				// itself is the smooth tail.
				BattleMusicClient.debug("PvP combat went cold with no mobs/boss; fading out now");
				beginFadeOut(false);
			}
		}

		// If a non-looping track ended mid-battle, roll another one.
		refreshFinishedTracks();

		tickChannels(dt);
	}

	private void startBattle(LocalPlayer player, boolean boss, boolean playerCombatHot, int count) {
		// Cheap mtime-gated rescan: walks disk only if a music folder was actually
		// modified since last scan. Avoids the per-battle disk hitch.
		library.rescanIfChanged();
		battleActive = true;
		heavyLatched = false;
		regularUsesBothPool = false;
		pvpPoolBattle = false;
		graceSecondsLeft = 0.0;

		boolean lowHp = player.getHealth() <= config.heavyHealthThreshold;
		boolean manyMobs = count >= config.heavyAggroMobCount;
		BattleMusicConfig.PvpMusicPool pool = (config.playerCombatMusicPool != null)
				? config.playerCombatMusicPool : BattleMusicConfig.PvpMusicPool.HEAVY;
		// who governs this battle's music:
		//  - a boss is always heavy, whatever the pool.
		//  - if the pvp trigger started this and the pool is REGULAR/BOTH, the pool governs the
		//    whole fight. pvp keeps you under the low-hp threshold constantly, so low hp must
		//    NOT force heavy here, only a boss does.
		//  - a pure mob battle (no pvp) keeps the normal rule: low hp -> heavy.
		boolean pvpPoolGoverned = playerCombatHot && pool != BattleMusicConfig.PvpMusicPool.HEAVY;
		BattleMusicClient.debug("BATTLE START (boss={}, hp={}, lowHp={}, playerDamageTrigger={}, pvpPool={}, poolGoverned={})",
				boss, player.getHealth(), lowHp, playerCombatHot, config.playerCombatMusicPool, pvpPoolGoverned);

		if (boss) {
			engageHeavy(true);
		} else if (manyMobs) {
			// A big swarm is always a heavy fight, regardless of health or pool.
			engageHeavy(true);
		} else if (pvpPoolGoverned) {
			pvpPoolBattle = true;
			if (pool == BattleMusicConfig.PvpMusicPool.BOTH) {
				regularUsesBothPool = true;
				engageBoth(true);
			} else {
				engageRegular(true);
			}
		} else if (lowHp) {
			engageHeavy(true);
		} else if (playerCombatHot) {
			engageHeavy(true);
		} else {
			engageRegular(true);
		}
	}

	private void maybeUpgradeToHeavy(LocalPlayer player, boolean boss, boolean playerCombatHot, int count) {
		if (heavyLatched) return;
		boolean lowHp = player.getHealth() <= config.heavyHealthThreshold;
		boolean manyMobs = count >= config.heavyAggroMobCount;
		// in a pvp-pool battle (REGULAR/BOTH) low hp must NOT escalate to heavy, since pvp
		// keeps you under the threshold constantly. only a boss escalates a pool-governed
		// battle.
		boolean lowHpForcesHeavy = lowHp && !pvpPoolBattle;
		// Mid-battle PvP only escalates to heavy when the configured pool is HEAVY.
		boolean pvpForcesHeavy = playerCombatHot
				&& config.playerCombatMusicPool == BattleMusicConfig.PvpMusicPool.HEAVY;
		if (boss || lowHpForcesHeavy || pvpForcesHeavy || manyMobs) {
			BattleMusicClient.debug("Upgrading to HEAVY (boss={}, lowHp={} hp={}<={}, pvpPoolBattle={}, playerDamageTrigger={}, pvpPool={})",
					boss, lowHp, player.getHealth(), config.heavyHealthThreshold,
					pvpPoolBattle, playerCombatHot, config.playerCombatMusicPool);
			engageHeavy(false);
		}
	}

	private void engageRegular(boolean allowResume) {
		if (!library.hasRegular()) {
			// no regular tracks. fall back to heavy so a battle still has music if the user
			// only filled the heavy folder. only stays silent when both folders are empty.
			if (library.hasHeavy()) {
				BattleMusicClient.debug("engageRegular: no regular tracks; falling back to the heavy folder");
				engageHeavy(allowResume);
			} else {
				BattleMusicClient.debug("engageRegular: no tracks at all, staying silent");
			}
			return;
		}
		phase = Phase.REGULAR;
		Path track;
		long startFrame = 0L;
		if (allowResume && canResume(resumeRegularFile)) {
			track = resumeRegularFile;
			startFrame = resumeRegularFrame;
			BattleMusicClient.debug("engageRegular: RESUMING {} at frame {} (within {}s)",
					track.getFileName(), startFrame, config.resumeWithinSeconds);
		} else {
			track = library.pickRegular();
			BattleMusicClient.debug("engageRegular: phase=REGULAR, track={}", track == null ? "<none>" : track.getFileName());
		}
		// Consume the resume token so a mid-battle re-roll cannot reuse it
		resumeRegularFile = null;
		// loop only when the folder has a single track. with more, play through and let
		// refreshFinishedTracks() roll the next one so it stays varied instead of one song
		// on repeat.
		boolean loop = library.regularCount() <= 1;
		if (track != null && regularChannel.start(track, loop, startFrame)) {
			regularChannel.fadeTo(1f, config.fadeInDurationSeconds, false);
			heavyChannel.fadeTo(0f, 0.25, true);
		} else {
			BattleMusicClient.debug("engageRegular: start failed, keeping current audio");
		}
	}

	private void engageHeavy(boolean allowResume) {
		heavyLatched = true;
		phase = Phase.HEAVY;
		Path track;
		long startFrame = 0L;
		if (allowResume && canResume(resumeHeavyFile)) {
			track = resumeHeavyFile;
			startFrame = resumeHeavyFrame;
			BattleMusicClient.debug("engageHeavy: RESUMING {} at frame {} (within {}s)",
					track.getFileName(), startFrame, config.resumeWithinSeconds);
		} else {
			// Heavy uses its own folder; fall back to regular folder if heavy is empty
			track = library.hasHeavy() ? library.pickHeavy()
					: (library.hasRegular() ? library.pickRegular() : null);
			// under the "both" pool the regular channel might already be playing this exact
			// file; crossfading it onto heavy makes it play twice with a slight offset.
			// re-roll to a different heavy track when we can
			Path nowPlaying = regularChannel.getLoaded();
			if (track != null && track.equals(nowPlaying) && library.heavyCount() > 1) {
				for (int i = 0; i < 6 && track.equals(nowPlaying); i++) track = library.pickHeavy();
			}
			BattleMusicClient.debug("engageHeavy: phase=HEAVY, track={}", track == null ? "<none>" : track.getFileName());
		}
		// Consume the resume token so a mid-battle re-roll cannot reuse it
		resumeHeavyFile = null;
		if (track == null) {
			BattleMusicClient.debug("engageHeavy: no tracks available (heavy or regular), staying silent");
			return;
		}
		// bring heavy in first and only cut regular once heavy actually started, so a failed
		// start (unreadable file) can't leave the battle silent. if regular is playing this is
		// a real crossfade: regular down while heavy up over the same window. on a cold start
		// there's nothing to cross so heavy just fades in normally. 0s = instant switch.
		// loop only with a single heavy track, else play through and continue via
		// refreshFinishedTracks().
		boolean loop = (library.hasHeavy() ? library.heavyCount() : library.regularCount()) <= 1;
		if (heavyChannel.start(track, loop, startFrame)) {
			boolean crossfading = regularChannel.isAudible();
			double heavyInSeconds = crossfading ? config.heavyCrossfadeSeconds : config.fadeInDurationSeconds;
			regularChannel.fadeTo(0f, config.heavyCrossfadeSeconds, true);
			heavyChannel.fadeTo(1f, heavyInSeconds, false);
			BattleMusicClient.debug("engageHeavy: {} regular -> HEAVY over {}s", crossfading ? "crossfading" : "fading in", heavyInSeconds);
		} else {
			BattleMusicClient.debug("engageHeavy: start failed for {}, keeping current audio", track.getFileName());
		}
	}

	/**
	 * pvp battle with pool=BOTH: one shared random pool across the Regular and Heavy
	 * folders, played on the regular channel (phase=REGULAR). a boss still escalates to
	 * heavy mid-fight; low hp does not, because a pvp-pool battle ignores the low-hp rule
	 * (see maybeUpgradeToHeavy).
	 */

	private void engageBoth(boolean allowResume) {
		if (!library.hasRegular() && !library.hasHeavy()) {
			BattleMusicClient.debug("engageBoth: no tracks available, staying silent");
			return;
		}
		phase = Phase.REGULAR;
		Path track;
		long startFrame = 0L;
		if (allowResume && canResume(resumeRegularFile)) {
			track = resumeRegularFile;
			startFrame = resumeRegularFrame;
			BattleMusicClient.debug("engageBoth: RESUMING {} at frame {} (within {}s)",
					track.getFileName(), startFrame, config.resumeWithinSeconds);
		} else {
			track = library.pickBoth();
			BattleMusicClient.debug("engageBoth: phase=REGULAR (PvP pool=both), track={}",
					track == null ? "<none>" : track.getFileName());
		}
		resumeRegularFile = null;
		// loop only when the combined pool has a single track, else play through and roll the
		// next one via refreshFinishedTracks().
		boolean loop = (library.regularCount() + library.heavyCount()) <= 1;
		if (track != null && regularChannel.start(track, loop, startFrame)) {
			regularChannel.fadeTo(1f, config.fadeInDurationSeconds, false);
			heavyChannel.fadeTo(0f, 0.25, true);
		} else {
			BattleMusicClient.debug("engageBoth: start failed, keeping current audio");
		}
	}

	private void beginFadeOut(boolean immediate) {
		double dur = immediate ? 0.2 : config.fadeOutDurationSeconds;
		BattleMusicClient.debug("FADE OUT (immediate={}, durationSeconds={})", immediate, dur);
		rememberResumePoint();
		regularChannel.fadeTo(0f, dur, true);
		heavyChannel.fadeTo(0f, dur, true);
		phase = Phase.IDLE;
		battleActive = false;
		heavyLatched = false;
		regularUsesBothPool = false;
		pvpPoolBattle = false;
		graceSecondsLeft = 0.0;
		aggro.clear();
	}

	/**
	 * snapshot what each channel is playing and where, so a battle that starts within
	 * {@code resumeWithinSeconds} can continue the track instead of restarting. taken when
	 * the fade-out begins, close enough for a music cue. channels not playing get their
	 * token cleared so a stale file never resumes.
	 */

	private void rememberResumePoint() {
		if (!config.battleResumeEnabled) return;
		boolean remembered = false;
		if (regularChannel.isLoaded()) {
			resumeRegularFile = regularChannel.getLoaded();
			resumeRegularFrame = regularChannel.getPlaybackFrame();
			remembered = true;
		} else {
			resumeRegularFile = null;
		}
		if (heavyChannel.isLoaded()) {
			resumeHeavyFile = heavyChannel.getLoaded();
			resumeHeavyFrame = heavyChannel.getPlaybackFrame();
			remembered = true;
		} else {
			resumeHeavyFile = null;
		}
		if (remembered) {
			resumeStampNanos = System.nanoTime();
			BattleMusicClient.debug("Remembered resume point: regular={}@{}, heavy={}@{}",
					resumeRegularFile == null ? "<none>" : resumeRegularFile.getFileName(), resumeRegularFrame,
					resumeHeavyFile == null ? "<none>" : resumeHeavyFile.getFileName(), resumeHeavyFrame);
		}
	}

	// True if {@code file} is a valid, still-readable resume target within the cooldown window.
	private boolean canResume(Path file) {
		if (!config.battleResumeEnabled || file == null || resumeStampNanos == 0L) return false;
		double age = (System.nanoTime() - resumeStampNanos) / 1_000_000_000.0;
		return age <= config.resumeWithinSeconds && Files.isReadable(file);
	}

	/** True while still inside the post-battle resume window, regardless of whether a resume
	 * track is available. Used to lower the mob bar for re-starting a battle (the "adrenaline
	 * keeps going" trigger). The window only ever opens when battle resume is enabled, since
	 * that is the only path that stamps {@code resumeStampNanos}. */

	private boolean inResumeWindow() {
		if (!config.battleResumeEnabled || resumeStampNanos == 0L) return false;
		double age = (System.nanoTime() - resumeStampNanos) / 1_000_000_000.0;
		return age <= config.resumeWithinSeconds;
	}

	private boolean isFadingOut() {
		return !battleActive && (regularChannel.isAudible() || heavyChannel.isAudible());
	}

	private void cancelFadeOutIfNeeded() {
		// if something pushed the active channel down (e.g. a fade-out started) but we're
		// still fighting, pull it back to full. only touch it when it's actually heading
		// below full: this runs every tick, and blindly re-firing fadeTo(1, fadeIn) here used
		// to stomp the short continuation fade every tick, dragging every song handoff back
		// over the long start fade (the dip between tracks) and messing up crossfades.
		MusicChannel active = (phase == Phase.HEAVY) ? heavyChannel
				: (phase == Phase.REGULAR) ? regularChannel : null;
		if (active != null && active.getTargetGain() < 1f) {
			active.fadeTo(1f, config.fadeInDurationSeconds, false);
		}
	}

	private void refreshFinishedTracks() {
		if (!battleActive) return;
		// check getLoaded() != null, NOT isLoaded(). a non-looping track that ends on its own
		// sets running=false (so isLoaded() is already false) but keeps its loaded path, which
		// is exactly the "track finished, roll the next" case we want. fade-out/hardStop clear
		// loaded so those are skipped. (isLoaded() here made multi-track folders play one song
		// then go silent.)
		if (phase == Phase.HEAVY && heavyChannel.getLoaded() != null && heavyChannel.isFinished()) {
			BattleMusicClient.debug("Heavy track finished; rolling another");
			engageHeavy(false);
			// the previous track already ended, so nothing to ease through. bring the next one
			// up quick instead of the long start swell so it feels continuous.
			if (heavyChannel.isLoaded()) heavyChannel.fadeTo(1f, continuationFadeSeconds(), false);
		} else if (phase == Phase.REGULAR && regularChannel.getLoaded() != null && regularChannel.isFinished()) {
			if (regularUsesBothPool) {
				BattleMusicClient.debug("Regular (PvP both-pool) track finished; rolling another");
				engageBoth(false);
			} else {
				BattleMusicClient.debug("Regular track finished; rolling another");
				engageRegular(false);
			}
			if (regularChannel.isLoaded()) regularChannel.fadeTo(1f, continuationFadeSeconds(), false);
		}
	}

	// fade for one track flowing into the next mid-battle. a track ends on its own tail so
	// the next comes in almost instantly (quick attack, not the long start swell), just
	// enough to dodge a click. capped by the configured fade-in so instant stays instant.
	private double continuationFadeSeconds() {
		return Math.min(0.25, config.fadeInDurationSeconds);
	}

	private void tickChannels(double dt) {
		regularChannel.update(dt);
		heavyChannel.update(dt);
	}

	// Once-per-second snapshot of the detection + audio state, gated by config.debug.
	private void logStatusThrottled(double dt, LocalPlayer player, int count, boolean boss, boolean qualifies, boolean playerCombatHot) {
		debugAccum += dt;
		if (debugAccum < 1.0) return;
		debugAccum = 0.0;
		BattleMusicClient.debug(
				"status: phase={} active={} heavyLatched={} | aggro={}/{} signals={} inRange={} boss={} qualifies={} "
						+ "| pvpDmg={}/{}HP combatHot={} combatLeft={}s | hp={} grace={}s | regGain={} heavyGain={}",
				phase, battleActive, heavyLatched,
				count, config.aggroMobCount, aggro.getLastAggroSignalCount(), aggro.getLastInRangeCount(),
				boss, qualifies,
				String.format(java.util.Locale.ROOT, "%.1f", damage.getRecentDamageHp()),
				String.format(java.util.Locale.ROOT, "%.1f", config.playerDamageThresholdHp),
				playerCombatHot,
				String.format(java.util.Locale.ROOT, "%.1f", playerCombatSecondsLeft),
				String.format(java.util.Locale.ROOT, "%.1f", player.getHealth()),
				String.format(java.util.Locale.ROOT, "%.1f", graceSecondsLeft),
				String.format(java.util.Locale.ROOT, "%.2f", regularChannel.getCurrentGain()),
				String.format(java.util.Locale.ROOT, "%.2f", heavyChannel.getCurrentGain()));
	}

	private double computeDeltaSeconds() {
		long now = System.nanoTime();
		if (lastTickNanos == 0L) {
			lastTickNanos = now;
			return 1.0 / 20.0;
		}
		double dt = (now - lastTickNanos) / 1_000_000_000.0;
		lastTickNanos = now;
		// Clamp to avoid huge jumps after a freeze/GC pause.
		return Math.max(0.0, Math.min(0.25, dt));
	}

	// player respawned: cut the held death-screen music and clear battle + resume state so
	// the next life starts clean. mirrors reset() but keeps lastTickNanos so the delta stays
	// smooth. bosses.clear() only drops the throttle cache (NOT the configured boss ids) so
	// a fast respawn can't read a stale boss hit from where you died.
	private void stopForRespawn() {
		regularChannel.hardStop();
		heavyChannel.hardStop();
		phase = Phase.IDLE;
		battleActive = false;
		heavyLatched = false;
		regularUsesBothPool = false;
		pvpPoolBattle = false;
		graceSecondsLeft = 0.0;
		playerCombatSecondsLeft = 0.0;
		playerCombatWasHot = false;
		deathHoldSecondsLeft = 0.0;
		resumeRegularFile = null;
		resumeHeavyFile = null;
		resumeStampNanos = 0L;
		aggro.clear();
		damage.clear();
		bosses.clear();
	}

	// Called on disconnect: silence everything and reset state.
	public void reset() {
		BattleMusicClient.debug("reset(): disconnect/world unload, silencing everything");
		regularChannel.hardStop();
		heavyChannel.hardStop();
		phase = Phase.IDLE;
		battleActive = false;
		heavyLatched = false;
		regularUsesBothPool = false;
		pvpPoolBattle = false;
		graceSecondsLeft = 0.0;
		playerCombatSecondsLeft = 0.0;
		playerCombatWasHot = false;
		deathHoldSecondsLeft = 0.0;
		playerWasDead = false;
		resumeRegularFile = null;
		resumeHeavyFile = null;
		resumeStampNanos = 0L;
		aggro.clear();
		damage.clear();
		bosses.clear();
		lastTickNanos = 0L;
	}

	// Re-read config-derived state (called when the settings screen saves).
	public void onConfigChanged() {
		bosses.refreshExtraIds();
	}
}

// easter egg