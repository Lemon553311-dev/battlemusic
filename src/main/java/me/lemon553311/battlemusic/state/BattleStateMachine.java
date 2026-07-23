package me.lemon553311.battlemusic.state;

import me.lemon553311.battlemusic.BattleMusicClient;
import me.lemon553311.battlemusic.lastheart.LastHeartFeature;
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
 * Central battle state machine: consumes the detection signals once per client tick
 * and drives the two music channels through the IDLE / REGULAR / HEAVY phases, with
 * fades, grace timers, death handling, and battle resume.
 *
 * @author Lemon553311
 * @author uxokpro1234
 * @author user2378
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
	// battle was started by the PvP trigger; suppresses the low-HP upgrade to HEAVY.
	private boolean pvpPoolBattle = false;

	// resume battle
	private Path resumeRegularFile = null;
	private long resumeRegularFrame = 0L;
	private Path resumeHeavyFile = null;
	private long resumeHeavyFrame = 0L;
	private long resumeStampNanos = 0L;
	// phase the battle was in when the resume point was taken. a battle that ended in
	// HEAVY must CONTINUE as heavy when the resume condition is met; without this flag
	// startBattle() re-evaluated heavy/regular from scratch and rolled a random regular
	// track instead of continuing the faded-out heavy one.
	private boolean resumeWasHeavy = false;

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

		if (!config.enabled || player == null || world == null) {
			// Animate fades down but do not evaluate detection.
			if (battleActive) beginFadeOut(true);
			playerCombatSecondsLeft = 0.0;
			playerCombatWasHot = false;
			damage.clear();
			tickChannels(dt);
			return;
		}

		if (client.isPaused()) {
			// Singleplayer pause (Esc): HOLD the battle instead of ending it. This used
			// to share the disabled-branch above, which hard-cut the music in 0.2s AND
			// wiped the PvP combat timer + damage window - so pausing for a second
			// mid-duel permanently killed the PvP music (no new hits arrive while
			// paused, so it could never re-qualify). Freeze evaluation and every battle
			// timer (nothing below runs), keep the current gains, and pick the fight
			// back up exactly where it was on unpause.
			suppressVanillaMusic(client);
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
			// Player respawned: cut the held death-screen music and clear battle state.
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

		boolean playerCombatHot = updatePlayerCombatHeat(dt, player, world);

		evaluateBattle(dt, player, count, boss, playerCombatHot);

		// If a non-looping track ended mid-battle, roll another one.
		refreshFinishedTracks();

		// Vanilla's own background music knows nothing about ours and would
		// happily start a track mid-battle, playing on top of the battle music.
		// While any battle channel is audible, keep vanilla's music stopped
		// (no-op when vanilla isn't playing anything).
		suppressVanillaMusic(client);

		tickChannels(dt);
	}

	/**
	 * PvP trigger bookkeeping: a burst of damage from another player forces heavy
	 * and can start a battle on its own. The combat-heat timer keeps it going
	 * through a duel, refreshed on every player hit so a long fight never cuts out
	 * but idling fades. Only player damage re-arms it: fall/lava/fire after a duel
	 * must NOT keep pvp music alive with nobody around.
	 *
	 * @return true while the pvp combat-heat timer is hot
	 */
	private boolean updatePlayerCombatHeat(double dt, LocalPlayer player, ClientLevel world) {
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
		return playerCombatHot;
	}

	/** Start, hold, escalate, or fade the battle based on this tick's detection results. */
	private void evaluateBattle(double dt, LocalPlayer player, int count, boolean boss, boolean playerCombatHot) {
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
	}

	private void suppressVanillaMusic(Minecraft client) {
		// Not compiled on the non-obfuscated 26.1+ tiers: the music-manager API
		// names there are unverified (same territory as the other non-obf
		// renames), so those targets keep the old behavior (vanilla music can
		// overlap) until someone confirms the 26.1+ name.
		//? if <26.1 {
		if (regularChannel.isAudible() || heavyChannel.isAudible()) {
			client.getMusicManager().stopPlaying();
		}
		//?}
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
		// Seed from THIS battle's trigger; it was never reset between battles, so a
		// stale `true` from an earlier mob fight made a pvp-only battle that went cold
		// on its first tick take the 15s mob-grace path instead of fading immediately.
		battleHeldByMobsOrBoss = count > 0 || boss;

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
			// "Last Heart Standing" visual: only when low HP alone makes it heavy.
			// playerCombatHot guards the pool==HEAVY case so PvP never shows it.
			if (!playerCombatHot) notifyHeavyFromLowHp();
		} else if (playerCombatHot) {
			engageHeavy(true);
		} else if (resumeWasHeavy && canResume(resumeHeavyFile)) {
			// The previous battle ended in HEAVY and we are re-starting within the resume
			// window: continue that heavy track (engageHeavy resumes resumeHeavyFile at its
			// remembered frame) instead of rolling a random regular song. engageHeavy also
			// re-latches heavy, so the continued battle stays heavy like it was.
			BattleMusicClient.debug("BATTLE RESUME: previous battle ended in HEAVY; continuing the heavy track instead of picking a regular one");
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
			// "Last Heart Standing" visual: only when the low-HP threshold is the
			// sole reason (no boss, swarm, or PvP involved), as requested.
			if (lowHpForcesHeavy && !boss && !manyMobs && !playerCombatHot) {
				notifyHeavyFromLowHp();
			}
		}
	}

	/** Fire the password-gated "Last Heart Standing" image (no-op unless enabled). */
	private void notifyHeavyFromLowHp() {
		LastHeartFeature f = BattleMusicClient.lastHeart();
		if (f != null) f.onHeavyFromLowHp();
	}

	// One resolved "what to play" decision for an engage call: either the resume
	// token (track + frame) or a fresh pick from the library.
	private static final class TrackChoice {
		final Path track;      // null when the picker came up empty
		final long startFrame; // > 0 only when resuming mid-track
		TrackChoice(Path track, long startFrame) {
			this.track = track;
			this.startFrame = startFrame;
		}
	}

	/**
	 * Resolve the resume token for an engage call, or null when this start should
	 * pick a fresh track instead (resume disabled, expired, or not requested).
	 */
	private TrackChoice resumeChoiceOr(boolean allowResume, Path resumeFile, long resumeFrame, String tag) {
		if (!allowResume || !canResume(resumeFile)) return null;
		BattleMusicClient.debug("{}: RESUMING {} at frame {} (within {}s)",
				tag, resumeFile.getFileName(), resumeFrame, config.resumeWithinSeconds);
		return new TrackChoice(resumeFile, resumeFrame);
	}

	/**
	 * Common tail of every engage call: apply the track's per-song settings and
	 * start it on {@code channel}. The per-song "start at" only applies on a
	 * fresh start; a resume keeps its frame. Returns true when playback started.
	 */
	private boolean startOnChannel(MusicChannel channel, TrackChoice choice, boolean loop) {
		double startSec = (choice.startFrame > 0L) ? 0.0 : library.startSecondsFor(choice.track);
		channel.setTrackGain(library.effectiveVolumeFor(choice.track));
		return choice.track != null && channel.start(choice.track, loop, choice.startFrame, startSec);
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
		Phase prevPhase = phase;
		phase = Phase.REGULAR;
		TrackChoice choice = resumeChoiceOr(allowResume, resumeRegularFile, resumeRegularFrame, "engageRegular");
		if (choice == null) {
			Path track = library.pickRegular();
			BattleMusicClient.debug("engageRegular: phase=REGULAR, track={}", track == null ? "<none>" : track.getFileName());
			choice = new TrackChoice(track, 0L);
		}
		// Consume the resume token so a mid-battle re-roll cannot reuse it
		resumeRegularFile = null;
		// loop only when the folder has a single track. with more, play through and let
		// refreshFinishedTracks() roll the next one so it stays varied instead of one song
		// on repeat.
		boolean loop = library.playableRegularCount() <= 1;
		if (startOnChannel(regularChannel, choice, loop)) {
			regularChannel.fadeTo(1f, config.fadeInDurationSeconds, false);
			heavyChannel.fadeTo(0f, 0.25, true);
		} else {
			BattleMusicClient.debug("engageRegular: start failed, keeping current audio");
			// Nothing started: put the phase back so the machine keeps managing
			// whatever IS actually playing (a phase pointing at a silent channel
			// starved refreshFinishedTracks/cancelFadeOutIfNeeded).
			phase = prevPhase;
		}
	}

	private void engageHeavy(boolean allowResume) {
		Phase prevPhase = phase;
		boolean prevLatched = heavyLatched;
		heavyLatched = true;
		phase = Phase.HEAVY;
		TrackChoice choice = resumeChoiceOr(allowResume, resumeHeavyFile, resumeHeavyFrame, "engageHeavy");
		if (choice == null) {
			// Heavy uses its own folder; fall back to regular folder if heavy is empty
			Path track = library.hasHeavy() ? library.pickHeavy()
					: (library.hasRegular() ? library.pickRegular() : null);
			// under the "both" pool the regular channel might already be playing this exact
			// file; crossfading it onto heavy makes it play twice with a slight offset.
			// re-roll to a different heavy track when we can
			Path nowPlaying = regularChannel.getLoaded();
			if (track != null && track.equals(nowPlaying) && library.playableHeavyCount() > 1) {
				// track != null guard inside the loop: the pickers can return null
				// if every heavy track got blacklisted as undecodable mid-battle.
				for (int i = 0; i < 6 && track != null && track.equals(nowPlaying); i++) track = library.pickHeavy();
			}
			BattleMusicClient.debug("engageHeavy: phase=HEAVY, track={}", track == null ? "<none>" : track.getFileName());
			choice = new TrackChoice(track, 0L);
		}
		// Consume the resume token so a mid-battle re-roll cannot reuse it
		resumeHeavyFile = null;
		if (choice.track == null) {
			BattleMusicClient.debug("engageHeavy: no tracks available (heavy or regular), staying silent");
			// Nothing to play: revert. Leaving heavyLatched + phase=HEAVY with a silent
			// heavy channel froze the battle - when the still-playing regular song
			// ended, refreshFinishedTracks (which watches the phase's channel) never
			// rolled the next track, so the rest of the battle stayed silent.
			phase = prevPhase;
			heavyLatched = prevLatched;
			return;
		}
		// bring heavy in first and only cut regular once heavy actually started, so a failed
		// start (unreadable file) can't leave the battle silent. if regular is playing this is
		// a real crossfade: regular down while heavy up over the same window. on a cold start
		// there's nothing to cross so heavy just fades in normally. 0s = instant switch.
		// loop only with a single heavy track, else play through and continue via
		// refreshFinishedTracks().
		boolean loop = (library.hasHeavy() ? library.playableHeavyCount() : library.playableRegularCount()) <= 1;
		if (startOnChannel(heavyChannel, choice, loop)) {
			boolean crossfading = regularChannel.isAudible();
			double heavyInSeconds = crossfading ? config.heavyCrossfadeSeconds : config.fadeInDurationSeconds;
			regularChannel.fadeTo(0f, config.heavyCrossfadeSeconds, true);
			heavyChannel.fadeTo(1f, heavyInSeconds, false);
			BattleMusicClient.debug("engageHeavy: {} regular -> HEAVY over {}s", crossfading ? "crossfading" : "fading in", heavyInSeconds);
		} else {
			BattleMusicClient.debug("engageHeavy: start failed for {}, keeping current audio", choice.track.getFileName());
			// Same revert as the track==null case: the escalation did not happen, so
			// don't latch it. maybeUpgradeToHeavy can retry next tick (the failed file
			// is blacklisted by MusicChannel.start, so the retry picks another one).
			phase = prevPhase;
			heavyLatched = prevLatched;
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
		Phase prevPhase = phase;
		phase = Phase.REGULAR;
		TrackChoice choice = resumeChoiceOr(allowResume, resumeRegularFile, resumeRegularFrame, "engageBoth");
		if (choice == null) {
			Path track = library.pickBoth();
			BattleMusicClient.debug("engageBoth: phase=REGULAR (PvP pool=both), track={}",
					track == null ? "<none>" : track.getFileName());
			choice = new TrackChoice(track, 0L);
		}
		// Consume the resume token so a mid-battle re-roll cannot reuse it
		resumeRegularFile = null;
		// loop only when the combined pool has a single track, else play through and roll the
		// next one via refreshFinishedTracks().
		boolean loop = (library.playableRegularCount() + library.playableHeavyCount()) <= 1;
		if (startOnChannel(regularChannel, choice, loop)) {
			regularChannel.fadeTo(1f, config.fadeInDurationSeconds, false);
			heavyChannel.fadeTo(0f, 0.25, true);
		} else {
			BattleMusicClient.debug("engageBoth: start failed, keeping current audio");
			// Same revert as engageRegular: nothing started, don't point the phase
			// at a silent channel.
			phase = prevPhase;
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
		// Taken while `phase` is still live (beginFadeOut only resets it to IDLE after
		// this call), so it records what the battle actually was when it faded out.
		resumeWasHeavy = (phase == Phase.HEAVY);
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
		clearBattleState();
	}

	// Called on disconnect: silence everything and reset state.
	public void reset() {
		BattleMusicClient.debug("reset(): disconnect/world unload, silencing everything");
		clearBattleState();
		playerWasDead = false;
		lastTickNanos = 0L;
	}

	// Shared teardown for respawn + disconnect: silence both channels and drop all
	// battle, timer, and resume state. bosses.clear() only drops the boss-hit throttle
	// cache (NOT the configured boss ids).
	private void clearBattleState() {
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
		resumeWasHeavy = false;
		aggro.clear();
		damage.clear();
		bosses.clear();
	}

	// Re-read config-derived state (called when the settings screen saves).
	public void onConfigChanged() {
		bosses.refreshExtraIds();
	}
}
