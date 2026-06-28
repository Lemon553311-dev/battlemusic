package me.lemon553311.battlemusic;

import me.lemon553311.battlemusic.audio.AudioEngine;
import me.lemon553311.battlemusic.audio.MusicLibrary;
import me.lemon553311.battlemusic.config.BattleMusicConfig;
import me.lemon553311.battlemusic.lasttotem.LastTotemFeature;
import me.lemon553311.battlemusic.lastheart.LastHeartFeature;
import me.lemon553311.battlemusic.state.BattleStateMachine;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 *
 * im gonna lose my mind with this holy shit.
 *
 */

public class BattleMusicClient implements ClientModInitializer {
	public static final String MOD_ID = "battlemusic";
	public static final Logger LOGGER = LoggerFactory.getLogger("BattleMusic");
	private static BattleMusicConfig config;
	private static AudioEngine audioEngine;
	private static MusicLibrary library;
	private static BattleStateMachine stateMachine;
	private static LastTotemFeature lastTotem;
	private static LastHeartFeature lastHeart;

	@Override
	public void onInitializeClient() {
		config = BattleMusicConfig.load();
		//dir regular/heavy battle
		library = new MusicLibrary(FabricLoader.getInstance().getGameDir().resolve(MOD_ID));
		library.ensureFolders();
		library.rescan();

        //audio engine
		audioEngine = new AudioEngine();
		stateMachine = new BattleStateMachine(config, audioEngine, library);

		// Secret, password-gated "Last Totem Standing" alert (off unless unlocked).
		lastTotem = new LastTotemFeature(config);
		lastTotem.init();

		// Secret, password-gated "Last Heart Standing" visual (off unless unlocked + enabled).
		lastHeart = new LastHeartFeature(config);
		lastHeart.init();

		ClientLifecycleEvents.CLIENT_STARTED.register(client -> audioEngine.init());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			stateMachine.reset();
			audioEngine.shutdown();
		});
		ClientTickEvents.END_CLIENT_TICK.register(stateMachine::onClientTick);


		//music mute on l;eave
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> stateMachine.reset());

		// trying to figure out why tf it doesn't work
		LOGGER.info("Battle Music initialised. Music folder: {} ({} regular, {} heavy track(s) found)",
				library.getRootFolder(), library.regularCount(), library.heavyCount());
		LOGGER.info("Battle Music config: enabled={}, debug={}, aggroMobCount={}, detectionRadius={}, "
						+ "heavyHealthThreshold={}, bossRadius={}, requireLineOfSight={} | "
						+ "pvpTrigger={} (>={} HP from players in {}s, combatTimeout {}s)",
				config.enabled, config.debug, config.aggroMobCount, config.detectionRadius,
				config.heavyHealthThreshold, config.bossRadius, config.requireLineOfSight,
				config.playerDamageTriggerEnabled, config.playerDamageThresholdHp,
				config.playerDamageWindowSeconds, config.playerCombatTimeoutSeconds);
	}


	//DEBUGG
	public static void debug(String format, Object... args) {
		BattleMusicConfig c = config;
		if (c != null && c.debug) {
			LOGGER.info("[battlemusic DEBUG] " + format, args);
		}
	}

	public static BattleMusicConfig config() {
		return config;
	}

	public static MusicLibrary library() {
		return library;
	}

	public static BattleStateMachine stateMachine() {
		return stateMachine;
	}

	public static LastHeartFeature lastHeart() {
		return lastHeart;
	}
}