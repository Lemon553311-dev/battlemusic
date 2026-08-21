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
			// singleplayer pause: HOLD the battle instead of ending it. fading here
			// also wiped the pvp timers, so pausing mid-duel killed the music for good.
			// freeze everything and pick it back up on unpause.
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

		// pvp trigger: a burst of damage from another player forces heavy and can start
		// a battle on its own. the combat-heat timer refreshes on every player hit so a
		// long fight never cuts out; only player damage re-arms it (fall/lava after a
		// duel must not keep pvp music alive with nobody around).
		damage.update(player, world, clientTick);
		boolean combatActivity = damage.receivedThisTick();
		if (damage.isTriggered()) {
			playerCombatSecondsLeft = config.playerCombatTimeoutSeconds; // arm on a qualifying burst
		} else if (combatActivity && playerCombatSecondsLeft > 0.0) {
			playerCombatSecondsLeft = config.playerCombatTimeoutSeconds; // any later hit keeps it hot
		}
		playerCombatSecondsLeft = Math.max(0.0, playerCombatSecondsLeft - dt);
		boolean playerCombatHot = playerCombatSecondsLeft > 0.0;
		if (playerCombatHot && !playerCombatWasHot) {
			BattleMusicClient.debug("PVP TRIGGER: took {} HP from another player within {}s -> forcing HEAVY (combat timeout {}s)",
					String.format(java.util.Locale.ROOT, "%.1f", damage.getRecentDamageHp()),
					config.playerDamageWindowSeconds, config.playerCombatTimeoutSeconds);
		}
		playerCombatWasHot = playerCombatHot;

		// inside the resume window the bar to re-start drops to resumeAggroMobCount
		// ("adrenaline keeps going"); cold starts still need the full count
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
				// what's actually holding the battle: mobs/boss can re-aggro so they get
				// the grace window; a pvp-timer-only battle doesn't need it
				battleHeldByMobsOrBoss = count > 0 || boss;
			} else if (battleHeldByMobsOrBoss) {
				// mobs/boss just emptied out: grace window before fading
				if (graceSecondsLeft <= 0.0 && !isFadingOut()) {
					graceSecondsLeft = config.fadeOutDelaySeconds;
					BattleMusicClient.debug("No aggro left; starting {}s grace timer before fade-out", config.fadeOutDelaySeconds);
				}
				graceSecondsLeft -= dt;
				if (graceSecondsLeft <= 0.0) {
					beginFadeOut(false);
				}
			} else {
				// pvp-only battle went cold: fade now instead of stacking grace on
				// top of the combat timeout
				BattleMusicClient.debug("PvP combat went cold with no mobs/boss; fading out now");
				beginFadeOut(false);
			}
		}

		// a non-looping track ended mid-battle -> roll another one
		refreshFinishedTracks();

		// keep vanilla's music stopped while ours is audible, it would otherwise
		// start a track on top of the battle music
		suppressVanillaMusic(client);

		tickChannels(dt);
	}

	private void suppressVanillaMusic(Minecraft client) {
		// not compiled on 26.1+ yet: music-manager API names there are unverified
		//? if <26.1 {
		if (regularChannel.isAudible() || heavyChannel.isAudible()) {
			client.getMusicManager().stopPlaying();
		}
		//?}
	}

	private void startBattle(LocalPlayer player, boolean boss, boolean playerCombatHot, int count) {
		// mtime-gated rescan: only walks disk if a folder actually changed
		library.rescanIfChanged();
		battleActive = true;
		heavyLatched = false;
		regularUsesBothPool = false;
		pvpPoolBattle = false;
		graceSecondsLeft = 0.0;
		// seed from THIS battle's trigger, or a stale true from an earlier mob fight
		// makes a pvp-only battle take the mob-grace path when it goes cold
		battleHeldByMobsOrBoss = count > 0 || boss;

		boolean lowHp = player.getHealth() <= config.heavyHealthThreshold;
		boolean manyMobs = count >= config.heavyAggroMobCount;
		BattleMusicConfig.PvpMusicPool pool = (config.playerCombatMusicPool != null)
				? config.playerCombatMusicPool : BattleMusicConfig.PvpMusicPool.HEAVY;
		// who governs this battle's music:
		//  - boss is always heavy
		//  - pvp with pool REGULAR/BOTH governs the whole fight (pvp keeps you under
		//    the low-hp threshold constantly, so low hp must not force heavy here)
		//  - pure mob battles keep the normal rule: low hp -> heavy
		boolean pvpPoolGoverned = playerCombatHot && pool != BattleMusicConfig.PvpMusicPool.HEAVY;
		BattleMusicClient.debug("BATTLE START (boss={}, hp={}, lowHp={}, playerDamageTrigger={}, pvpPool={}, poolGoverned={})",
				boss, player.getHealth(), lowHp, playerCombatHot, config.playerCombatMusicPool, pvpPoolGoverned);

		if (boss) {
			engageHeavy(true);
		} else if (manyMobs) {
			// a big swarm is always heavy
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
			// last-heart visual only when low hp alone made it heavy, never for pvp
			if (!playerCombatHot) notifyHeavyFromLowHp();
		} else if (playerCombatHot) {
			engageHeavy(true);
		} else if (resumeWasHeavy && canResume(resumeHeavyFile)) {
			// previous battle ended in heavy and we're inside the resume window:
			// continue that track instead of rolling a random regular one
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
		// in a pvp-pool battle low hp must not escalate (pvp keeps you under the
		// threshold constantly); only a boss escalates those
		boolean lowHpForcesHeavy = lowHp && !pvpPoolBattle;
		// mid-battle pvp only escalates when the pool is HEAVY
		boolean pvpForcesHeavy = playerCombatHot
				&& config.playerCombatMusicPool == BattleMusicConfig.PvpMusicPool.HEAVY;
		if (boss || lowHpForcesHeavy || pvpForcesHeavy || manyMobs) {
			BattleMusicClient.debug("Upgrading to HEAVY (boss={}, lowHp={} hp={}<={}, pvpPoolBattle={}, playerDamageTrigger={}, pvpPool={})",
					boss, lowHp, player.getHealth(), config.heavyHealthThreshold,
					pvpPoolBattle, playerCombatHot, config.playerCombatMusicPool);
			engageHeavy(false);
			// last-heart visual only when low hp is the sole reason
			if (lowHpForcesHeavy && !boss && !manyMobs && !playerCombatHot) {
				notifyHeavyFromLowHp();
			}
		}
	}

	// fire the "Last Heart Standing" visual (no-op unless enabled)
	private void notifyHeavyFromLowHp() {
		LastHeartFeature f = BattleMusicClient.lastHeart();
		if (f != null) f.onHeavyFromLowHp();
	}

	private void engageRegular(boolean allowResume) {
		if (!library.hasRegular()) {
			// no regular tracks: fall back to heavy so there's still music
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
		// consume the resume token so a re-roll can't reuse it
		resumeRegularFile = null;
		// loop only with a single track; otherwise play through and let
		// refreshFinishedTracks() roll the next one
		boolean loop = library.playableRegularCount() <= 1;
		double startSec = (startFrame > 0L) ? 0.0 : library.startSecondsFor(track);
		regularChannel.setTrackGain(library.effectiveVolumeFor(track));
		if (track != null && regularChannel.start(track, loop, startFrame, startSec)) {
			regularChannel.fadeTo(1f, config.fadeInDurationSeconds, false);
			heavyChannel.fadeTo(0f, 0.25, true);
		} else {
			BattleMusicClient.debug("engageRegular: start failed, keeping current audio");
			// nothing started: revert the phase so the machine keeps managing whatever
			// IS playing (a phase on a silent channel starves refreshFinishedTracks)
			phase = prevPhase;
		}
	}

	private void engageHeavy(boolean allowResume) {
		Phase prevPhase = phase;
		boolean prevLatched = heavyLatched;
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
			// heavy uses its own folder, falls back to regular if empty
			track = library.hasHeavy() ? library.pickHeavy()
					: (library.hasRegular() ? library.pickRegular() : null);
			// with the "both" pool the regular channel may already be playing this exact
			// file; crossfading it onto heavy would play it twice
			Path nowPlaying = regularChannel.getLoaded();
			if (track != null && track.equals(nowPlaying) && library.playableHeavyCount() > 1) {
				for (int i = 0; i < 6 && track != null && track.equals(nowPlaying); i++) track = library.pickHeavy();
			}
			BattleMusicClient.debug("engageHeavy: phase=HEAVY, track={}", track == null ? "<none>" : track.getFileName());
		}
		// consume the resume token so a re-roll can't reuse it
		resumeHeavyFile = null;
		if (track == null) {
			BattleMusicClient.debug("engageHeavy: no tracks available (heavy or regular), staying silent");
			// nothing to play: revert. leaving heavyLatched + phase=HEAVY on a silent
			// channel froze the battle when the regular song ended.
			phase = prevPhase;
			heavyLatched = prevLatched;
			return;
		}
		// bring heavy in first and only cut regular once it actually started, so a failed
		// start can't leave the battle silent. loop only with a single heavy track.
		boolean loop = (library.hasHeavy() ? library.playableHeavyCount() : library.playableRegularCount()) <= 1;
		double startSec = (startFrame > 0L) ? 0.0 : library.startSecondsFor(track);
		heavyChannel.setTrackGain(library.effectiveVolumeFor(track));
		if (heavyChannel.start(track, loop, startFrame, startSec)) {
			boolean crossfading = regularChannel.isAudible();
			double heavyInSeconds = crossfading ? config.heavyCrossfadeSeconds : config.fadeInDurationSeconds;
			regularChannel.fadeTo(0f, config.heavyCrossfadeSeconds, true);
			heavyChannel.fadeTo(1f, heavyInSeconds, false);
			BattleMusicClient.debug("engageHeavy: {} regular -> HEAVY over {}s", crossfading ? "crossfading" : "fading in", heavyInSeconds);
		} else {
			BattleMusicClient.debug("engageHeavy: start failed for {}, keeping current audio", track.getFileName());
			// escalation didn't happen, don't latch it; maybeUpgradeToHeavy retries next
			// tick (the failed file is blacklisted so the retry picks another)
			phase = prevPhase;
			heavyLatched = prevLatched;
		}
	}

	/**
	 * pvp battle with pool=BOTH: one shared pool across both folders, played on
	 * the regular channel. a boss still escalates; low hp does not (see
	 * maybeUpgradeToHeavy).
	 */

	private void engageBoth(boolean allowResume) {
		if (!library.hasRegular() && !library.hasHeavy()) {
			BattleMusicClient.debug("engageBoth: no tracks available, staying silent");
			return;
		}
		Phase prevPhase = phase;
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
		boolean loop = (library.playableRegularCount() + library.playableHeavyCount()) <= 1;
		double startSec = (startFrame > 0L) ? 0.0 : library.startSecondsFor(track);
		regularChannel.setTrackGain(library.effectiveVolumeFor(track));
		if (track != null && regularChannel.start(track, loop, startFrame, startSec)) {
			regularChannel.fadeTo(1f, config.fadeInDurationSeconds, false);
			heavyChannel.fadeTo(0f, 0.25, true);
		} else {
			BattleMusicClient.debug("engageBoth: start failed, keeping current audio");
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
	 * snapshot what each channel is playing so a battle starting within
	 * {@code resumeWithinSeconds} can continue the track. channels not playing
	 * get their token cleared.
	 */

	private void rememberResumePoint() {
		if (!config.battleResumeEnabled) return;
		// taken while phase is still live, records what the battle actually was
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

	// true if file is a readable resume target within the cooldown window
	private boolean canResume(Path file) {
		if (!config.battleResumeEnabled || file == null || resumeStampNanos == 0L) return false;
		double age = (System.nanoTime() - resumeStampNanos) / 1_000_000_000.0;
		return age <= config.resumeWithinSeconds && Files.isReadable(file);
	}

	// true while inside the post-battle resume window; lowers the mob bar for
	// re-starting a battle ("adrenaline keeps going")

	private boolean inResumeWindow() {
		if (!config.battleResumeEnabled || resumeStampNanos == 0L) return false;
		double age = (System.nanoTime() - resumeStampNanos) / 1_000_000_000.0;
		return age <= config.resumeWithinSeconds;
	}

	private boolean isFadingOut() {
		return !battleActive && (regularChannel.isAudible() || heavyChannel.isAudible());
	}

	private void cancelFadeOutIfNeeded() {
		// if something pushed the active channel down but we're still fighting, pull it
		// back to full. only when actually below target: re-firing fadeTo(1, fadeIn)
		// every tick would stomp the short continuation fade between tracks.
		MusicChannel active = (phase == Phase.HEAVY) ? heavyChannel
				: (phase == Phase.REGULAR) ? regularChannel : null;
		if (active != null && active.getTargetGain() < 1f) {
			active.fadeTo(1f, config.fadeInDurationSeconds, false);
		}
	}

	private void refreshFinishedTracks() {
		if (!battleActive) return;
		// check getLoaded() != null, NOT isLoaded(): a finished non-looping track sets
		// running=false but keeps its loaded path - exactly the "roll the next" case.
		// fade-out/hardStop clear loaded so those are skipped.
		if (phase == Phase.HEAVY && heavyChannel.getLoaded() != null && heavyChannel.isFinished()) {
			BattleMusicClient.debug("Heavy track finished; rolling another");
			engageHeavy(false);
			// previous track already ended: quick fade in, not the long start swell
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

	// fade for one track flowing into the next mid-battle; just enough to dodge
	// a click, capped by the configured fade-in
	private double continuationFadeSeconds() {
		return Math.min(0.25, config.fadeInDurationSeconds);
	}

	private void tickChannels(double dt) {
		regularChannel.update(dt);
		heavyChannel.update(dt);
	}

	// once-per-second status snapshot, gated by config.debug
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
		// clamp huge jumps after a freeze/GC pause
		return Math.max(0.0, Math.min(0.25, dt));
	}

	// respawn: cut the held death-screen music and clear battle + resume state.
	// keeps lastTickNanos so the delta stays smooth; bosses.clear() only drops the
	// throttle cache so a fast respawn can't read a stale hit from where you died.
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
		resumeWasHeavy = false;
		aggro.clear();
		damage.clear();
		bosses.clear();
	}

	// disconnect: silence everything and reset
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
		resumeWasHeavy = false;
		aggro.clear();
		damage.clear();
		bosses.clear();
		lastTickNanos = 0L;
	}

	// re-read config-derived state (settings screen saved)
	public void onConfigChanged() {
		bosses.refreshExtraIds();
	}
}

// easter egg