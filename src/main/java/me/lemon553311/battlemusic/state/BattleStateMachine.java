package me.lemon553311.battlemusic.state;

import me.lemon553311.battlemusic.BattleMusicClient;
import me.lemon553311.battlemusic.audio.AudioEngine;
import me.lemon553311.battlemusic.audio.MusicChannel;
import me.lemon553311.battlemusic.audio.MusicLibrary;
import me.lemon553311.battlemusic.config.BattleMusicConfig;
import me.lemon553311.battlemusic.detection.AggroTracker;
import me.lemon553311.battlemusic.detection.BossDetector;
import me.lemon553311.battlemusic.detection.PlayerDamageTracker;
import me.lemon553311.battlemusic.lastheart.LastHeartFeature;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundSource;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Drives the battle music state machine. Called once per client tick from the
 * loader-specific bootstrap ({@link me.lemon553311.battlemusic.BattleMusicClient},
 * {@link me.lemon553311.battlemusic.BattleMusicForge},
 * {@link me.lemon553311.battlemusic.BattleMusicNeoForge}).
 */
public class BattleStateMachine {
	private final BattleMusicConfig config;
	private final AudioEngine engine;
	private final MusicLibrary library;
	private final AggroTracker aggro;
	private final BossDetector bosses;
	private final PlayerDamageTracker damage;

	private final MusicChannel regularChannel;
	private final MusicChannel heavyChannel;

	private BattlePhase phase = BattlePhase.IDLE;
	private boolean battleActive = false;
	private boolean heavyLatched = false;
	private double graceSecondsLeft = 0.0;
	private boolean battleHeldByMobsOrBoss = false;
	private double playerCombatSecondsLeft = 0.0;
	private boolean playerCombatWasHot = false;
	private boolean playerWasDead = false;
	private static final double DEATH_HOLD_MAX_SECONDS = 15.0;
	private double deathHoldSecondsLeft = 0.0;
	private boolean regularUsesBothPool = false;
	private boolean pvpPoolBattle = false;

	private final ResumeManager resume = new ResumeManager();

	private long lastTickNanos = 0L;
	private double debugAccum = 0.0;
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

		if (!engine.isReady()) {
			tickChannels(dt);
			return;
		}

		float mcVolume = client.options.getSoundSourceVolume(SoundSource.MASTER)
				* client.options.getSoundSourceVolume(SoundSource.MUSIC);
		regularChannel.setOutputVolume(mcVolume);
		heavyChannel.setOutputVolume(mcVolume);

		LocalPlayer player = client.player;
		ClientLevel world = client.level;

		if (!config.enabled || player == null || world == null) {
			if (battleActive) beginFadeOut(true);
			playerCombatSecondsLeft = 0.0;
			playerCombatWasHot = false;
			damage.clear();
			tickChannels(dt);
			return;
		}

		if (client.isPaused()) {
			suppressVanillaMusic(client);
			tickChannels(dt);
			return;
		}

		if (player.isDeadOrDying()) {
			if (!playerWasDead) {
				playerWasDead = true;
				deathHoldSecondsLeft = DEATH_HOLD_MAX_SECONDS;
			}
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
			playerWasDead = false;
			stopForRespawn();
			tickChannels(dt);
			return;
		}

		clientTick++;

		aggro.update(player, world, clientTick);
		int count = aggro.getAggroCount();
		boolean boss = bosses.anyBossNearby(player, world, clientTick);

		damage.update(player, world, clientTick);
		boolean combatActivity = damage.receivedThisTick();
		if (damage.isTriggered()) {
			playerCombatSecondsLeft = config.playerCombatTimeoutSeconds;
		} else if (combatActivity && playerCombatSecondsLeft > 0.0) {
			playerCombatSecondsLeft = config.playerCombatTimeoutSeconds;
		}
		playerCombatSecondsLeft = Math.max(0.0, playerCombatSecondsLeft - dt);
		boolean playerCombatHot = playerCombatSecondsLeft > 0.0;
		if (playerCombatHot && !playerCombatWasHot) {
			BattleMusicClient.debug("PVP TRIGGER: took {} HP from another player within {}s -> forcing HEAVY (combat timeout {}s)",
					String.format(java.util.Locale.ROOT, "%.1f", damage.getRecentDamageHp()),
					config.playerDamageWindowSeconds, config.playerCombatTimeoutSeconds);
		}
		playerCombatWasHot = playerCombatHot;

		int mobThreshold = (!battleActive && resume.inWindow(config.battleResumeEnabled, config.resumeWithinSeconds))
				? config.resumeAggroMobCount : config.aggroMobCount;
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
				battleHeldByMobsOrBoss = count > 0 || boss;
			} else if (battleHeldByMobsOrBoss) {
				if (graceSecondsLeft <= 0.0 && !isFadingOut()) {
					graceSecondsLeft = config.fadeOutDelaySeconds;
					BattleMusicClient.debug("No aggro left; starting {}s grace timer before fade-out", config.fadeOutDelaySeconds);
				}
				graceSecondsLeft -= dt;
				if (graceSecondsLeft <= 0.0) {
					beginFadeOut(false);
				}
			} else {
				BattleMusicClient.debug("PvP combat went cold with no mobs/boss; fading out now");
				beginFadeOut(false);
			}
		}

		refreshFinishedTracks();
		suppressVanillaMusic(client);
		tickChannels(dt);
	}

	private void suppressVanillaMusic(Minecraft client) {
		//? if <26.1 {
		if (regularChannel.isAudible() || heavyChannel.isAudible()) {
			client.getMusicManager().stopPlaying();
		}
		//?}
	}

	private void startBattle(LocalPlayer player, boolean boss, boolean playerCombatHot, int count) {
		library.rescanIfChanged();
		battleActive = true;
		heavyLatched = false;
		regularUsesBothPool = false;
		pvpPoolBattle = false;
		graceSecondsLeft = 0.0;
		battleHeldByMobsOrBoss = count > 0 || boss;

		boolean lowHp = player.getHealth() <= config.heavyHealthThreshold;
		boolean manyMobs = count >= config.heavyAggroMobCount;
		BattleMusicConfig.PvpMusicPool pool = (config.playerCombatMusicPool != null)
				? config.playerCombatMusicPool : BattleMusicConfig.PvpMusicPool.HEAVY;
		boolean pvpPoolGoverned = playerCombatHot && pool != BattleMusicConfig.PvpMusicPool.HEAVY;

		BattleMusicClient.debug("BATTLE START (boss={}, hp={}, lowHp={}, playerDamageTrigger={}, pvpPool={}, poolGoverned={})",
				boss, player.getHealth(), lowHp, playerCombatHot, config.playerCombatMusicPool, pvpPoolGoverned);

		if (boss) {
			engageHeavy(true);
		} else if (manyMobs) {
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
			if (!playerCombatHot) notifyHeavyFromLowHp();
		} else if (playerCombatHot) {
			engageHeavy(true);
		} else if (resume.wasHeavy() && resume.canResume(resume.heavyFile(), config.battleResumeEnabled, config.resumeWithinSeconds)) {
			BattleMusicClient.debug("BATTLE RESUME: previous battle ended in HEAVY; continuing the heavy track");
			engageHeavy(true);
		} else {
			engageRegular(true);
		}
	}

	private void maybeUpgradeToHeavy(LocalPlayer player, boolean boss, boolean playerCombatHot, int count) {
		if (heavyLatched) return;
		boolean lowHp = player.getHealth() <= config.heavyHealthThreshold;
		boolean manyMobs = count >= config.heavyAggroMobCount;
		boolean lowHpForcesHeavy = lowHp && !pvpPoolBattle;
		boolean pvpForcesHeavy = playerCombatHot
				&& config.playerCombatMusicPool == BattleMusicConfig.PvpMusicPool.HEAVY;
		if (boss || lowHpForcesHeavy || pvpForcesHeavy || manyMobs) {
			BattleMusicClient.debug("Upgrading to HEAVY (boss={}, lowHp={} hp={}<={}, pvpPoolBattle={}, playerDamageTrigger={}, pvpPool={})",
					boss, lowHp, player.getHealth(), config.heavyHealthThreshold,
					pvpPoolBattle, playerCombatHot, config.playerCombatMusicPool);
			engageHeavy(false);
			if (lowHpForcesHeavy && !boss && !manyMobs && !playerCombatHot) {
				notifyHeavyFromLowHp();
			}
		}
	}

	private void notifyHeavyFromLowHp() {
		LastHeartFeature f = BattleMusicClient.lastHeart();
		if (f != null) f.onHeavyFromLowHp();
	}

	private void engageRegular(boolean allowResume) {
		if (!library.hasRegular()) {
			if (library.hasHeavy()) {
				BattleMusicClient.debug("engageRegular: no regular tracks; falling back to the heavy folder");
				engageHeavy(allowResume);
			} else {
				BattleMusicClient.debug("engageRegular: no tracks at all, staying silent");
			}
			return;
		}
		BattlePhase prevPhase = phase;
		phase = BattlePhase.REGULAR;
		Path track;
		long startFrame = 0L;
		if (allowResume && resume.canResume(resume.regularFile(), config.battleResumeEnabled, config.resumeWithinSeconds)) {
			track = resume.regularFile();
			startFrame = resume.regularFrame();
			BattleMusicClient.debug("engageRegular: RESUMING {} at frame {} (within {}s)",
					track.getFileName(), startFrame, config.resumeWithinSeconds);
		} else {
			track = library.pickRegular();
			BattleMusicClient.debug("engageRegular: phase=REGULAR, track={}", track == null ? "<none>" : track.getFileName());
		}
		resume.regularFile(); // consume by clearing below
		resume.clear();
		boolean loop = library.playableRegularCount() <= 1;
		double startSec = (startFrame > 0L) ? 0.0 : library.startSecondsFor(track);
		regularChannel.setTrackGain(library.effectiveVolumeFor(track));
		if (track != null && regularChannel.start(track, loop, startFrame, startSec)) {
			regularChannel.fadeTo(1f, (float) config.fadeInDurationSeconds, false);
			heavyChannel.fadeTo(0f, 0.25f, true);
		} else {
			BattleMusicClient.debug("engageRegular: start failed, keeping current audio");
			phase = prevPhase;
		}
	}

	private void engageHeavy(boolean allowResume) {
		BattlePhase prevPhase = phase;
		boolean prevLatched = heavyLatched;
		heavyLatched = true;
		phase = BattlePhase.HEAVY;
		Path track;
		long startFrame = 0L;
		if (allowResume && resume.canResume(resume.heavyFile(), config.battleResumeEnabled, config.resumeWithinSeconds)) {
			track = resume.heavyFile();
			startFrame = resume.heavyFrame();
			BattleMusicClient.debug("engageHeavy: RESUMING {} at frame {} (within {}s)",
					track.getFileName(), startFrame, config.resumeWithinSeconds);
		} else {
			track = library.hasHeavy() ? library.pickHeavy()
					: (library.hasRegular() ? library.pickRegular() : null);
			Path nowPlaying = regularChannel.getLoaded();
			if (track != null && track.equals(nowPlaying) && library.playableHeavyCount() > 1) {
				for (int i = 0; i < 6 && track != null && track.equals(nowPlaying); i++) track = library.pickHeavy();
			}
			BattleMusicClient.debug("engageHeavy: phase=HEAVY, track={}", track == null ? "<none>" : track.getFileName());
		}
		resume.clear();
		if (track == null) {
			BattleMusicClient.debug("engageHeavy: no tracks available (heavy or regular), staying silent");
			phase = prevPhase;
			heavyLatched = prevLatched;
			return;
		}
		boolean loop = (library.hasHeavy() ? library.playableHeavyCount() : library.playableRegularCount()) <= 1;
		double startSec = (startFrame > 0L) ? 0.0 : library.startSecondsFor(track);
		heavyChannel.setTrackGain(library.effectiveVolumeFor(track));
		if (heavyChannel.start(track, loop, startFrame, startSec)) {
			boolean crossfading = regularChannel.isAudible();
			double heavyInSeconds = crossfading ? config.heavyCrossfadeSeconds : config.fadeInDurationSeconds;
			regularChannel.fadeTo(0f, (float) config.heavyCrossfadeSeconds, true);
			heavyChannel.fadeTo(1f, (float) heavyInSeconds, false);
			BattleMusicClient.debug("engageHeavy: {} regular -> HEAVY over {}s", crossfading ? "crossfading" : "fading in", heavyInSeconds);
		} else {
			BattleMusicClient.debug("engageHeavy: start failed for {}, keeping current audio", track.getFileName());
			phase = prevPhase;
			heavyLatched = prevLatched;
		}
	}

	private void engageBoth(boolean allowResume) {
		if (!library.hasRegular() && !library.hasHeavy()) {
			BattleMusicClient.debug("engageBoth: no tracks available, staying silent");
			return;
		}
		BattlePhase prevPhase = phase;
		phase = BattlePhase.REGULAR;
		Path track;
		long startFrame = 0L;
		if (allowResume && resume.canResume(resume.regularFile(), config.battleResumeEnabled, config.resumeWithinSeconds)) {
			track = resume.regularFile();
			startFrame = resume.regularFrame();
			BattleMusicClient.debug("engageBoth: RESUMING {} at frame {} (within {}s)",
					track.getFileName(), startFrame, config.resumeWithinSeconds);
		} else {
			track = library.pickBoth();
			BattleMusicClient.debug("engageBoth: phase=REGULAR (PvP pool=both), track={}",
					track == null ? "<none>" : track.getFileName());
		}
		resume.clear();
		boolean loop = (library.playableRegularCount() + library.playableHeavyCount()) <= 1;
		double startSec = (startFrame > 0L) ? 0.0 : library.startSecondsFor(track);
		regularChannel.setTrackGain(library.effectiveVolumeFor(track));
		if (track != null && regularChannel.start(track, loop, startFrame, startSec)) {
			regularChannel.fadeTo(1f, (float) config.fadeInDurationSeconds, false);
			heavyChannel.fadeTo(0f, 0.25f, true);
		} else {
			BattleMusicClient.debug("engageBoth: start failed, keeping current audio");
			phase = prevPhase;
		}
	}

	private void beginFadeOut(boolean immediate) {
		double dur = immediate ? 0.2 : config.fadeOutDurationSeconds;
		BattleMusicClient.debug("FADE OUT (immediate={}, durationSeconds={})", immediate, dur);
		resume.remember(phase == BattlePhase.HEAVY, regularChannel.isLoaded(), regularChannel.getLoaded(), regularChannel.getPlaybackFrame(),
				heavyChannel.isLoaded(), heavyChannel.getLoaded(), heavyChannel.getPlaybackFrame());
		regularChannel.fadeTo(0f, (float) dur, true);
		heavyChannel.fadeTo(0f, (float) dur, true);
		phase = BattlePhase.IDLE;
		battleActive = false;
		heavyLatched = false;
		regularUsesBothPool = false;
		pvpPoolBattle = false;
		graceSecondsLeft = 0.0;
		aggro.clear();
	}

	private boolean isFadingOut() {
		return !battleActive && (regularChannel.isAudible() || heavyChannel.isAudible());
	}

	private void cancelFadeOutIfNeeded() {
		MusicChannel active = (phase == BattlePhase.HEAVY) ? heavyChannel
				: (phase == BattlePhase.REGULAR) ? regularChannel : null;
		if (active != null && active.getTargetGain() < 1f) {
			active.fadeTo(1f, (float) config.fadeInDurationSeconds, false);
		}
	}

	private void refreshFinishedTracks() {
		if (!battleActive) return;
		if (phase == BattlePhase.HEAVY && heavyChannel.getLoaded() != null && heavyChannel.isFinished()) {
			BattleMusicClient.debug("Heavy track finished; rolling another");
			engageHeavy(false);
			if (heavyChannel.isLoaded()) heavyChannel.fadeTo(1f, (float) continuationFadeSeconds(), false);
		} else if (phase == BattlePhase.REGULAR && regularChannel.getLoaded() != null && regularChannel.isFinished()) {
			if (regularUsesBothPool) {
				BattleMusicClient.debug("Regular (PvP both-pool) track finished; rolling another");
				engageBoth(false);
			} else {
				BattleMusicClient.debug("Regular track finished; rolling another");
				engageRegular(false);
			}
			if (regularChannel.isLoaded()) regularChannel.fadeTo(1f, (float) continuationFadeSeconds(), false);
		}
	}

	private double continuationFadeSeconds() {
		return Math.min(0.25, config.fadeInDurationSeconds);
	}

	private void tickChannels(double dt) {
		regularChannel.update(dt);
		heavyChannel.update(dt);
	}

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
		return Math.max(0.0, Math.min(0.25, dt));
	}

	private void stopForRespawn() {
		regularChannel.hardStop();
		heavyChannel.hardStop();
		phase = BattlePhase.IDLE;
		battleActive = false;
		heavyLatched = false;
		regularUsesBothPool = false;
		pvpPoolBattle = false;
		graceSecondsLeft = 0.0;
		playerCombatSecondsLeft = 0.0;
		playerCombatWasHot = false;
		deathHoldSecondsLeft = 0.0;
		resume.clear();
		aggro.clear();
		damage.clear();
		bosses.clear();
	}

	public void reset() {
		BattleMusicClient.debug("reset(): disconnect/world unload, silencing everything");
		regularChannel.hardStop();
		heavyChannel.hardStop();
		phase = BattlePhase.IDLE;
		battleActive = false;
		heavyLatched = false;
		regularUsesBothPool = false;
		pvpPoolBattle = false;
		graceSecondsLeft = 0.0;
		playerCombatSecondsLeft = 0.0;
		playerCombatWasHot = false;
		deathHoldSecondsLeft = 0.0;
		playerWasDead = false;
		resume.clear();
		aggro.clear();
		damage.clear();
		bosses.clear();
		lastTickNanos = 0L;
	}

	public void onConfigChanged() {
		bosses.refreshExtraIds();
	}
}
