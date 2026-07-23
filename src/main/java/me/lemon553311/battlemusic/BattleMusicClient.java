package me.lemon553311.battlemusic;

import me.lemon553311.battlemusic.audio.AudioEngine;
import me.lemon553311.battlemusic.audio.MusicLibrary;
import me.lemon553311.battlemusic.config.BattleMusicConfig;
import me.lemon553311.battlemusic.lasttotem.LastTotemFeature;
import me.lemon553311.battlemusic.lastheart.LastHeartFeature;
import me.lemon553311.battlemusic.platform.Platform;
import me.lemon553311.battlemusic.state.BattleStateMachine;

//? if fabric {
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
//?}

import net.minecraft.client.Minecraft;

//? if >=1.17 {
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
//?} else {
/*import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
*///?}

/**
 * Loader-neutral core of the mod + the Fabric client entrypoint.
 *
 * Multi-loader layout (Stonecutter //? fabric/forge/neoforge constants):
 *   - Fabric: this class IS the entrypoint (ClientModInitializer) and wires
 *     the Fabric lifecycle/tick/disconnect events to the static hooks below.
 *   - Forge:    {@link BattleMusicForge} constructs everything via init() and
 *     wires the equivalent Forge events to the same hooks.
 *   - NeoForge: {@link BattleMusicNeoForge}, same idea.
 * Everything the mod actually does lives behind the static hooks, so the three
 * bootstraps stay tiny.
 *
 * Loader-neutral core of the mod + the Fabric client entrypoint.
 */
//? if fabric {
public class BattleMusicClient implements ClientModInitializer {
//?} else {
/*public class BattleMusicClient {
*///?}
	public static final String MOD_ID = "battlemusic";
	//? if >=1.17 {
	public static final Logger LOGGER = LoggerFactory.getLogger("BattleMusic");
	//?} else {
	/*public static final Logger LOGGER = LogManager.getLogger("BattleMusic");
	*///?}
	private static BattleMusicConfig config;
	private static AudioEngine audioEngine;
	private static MusicLibrary library;
	private static BattleStateMachine stateMachine;
	private static LastTotemFeature lastTotem;
	private static LastHeartFeature lastHeart;

	//? if fabric {
	@Override
	public void onInitializeClient() {
		init();

		ClientLifecycleEvents.CLIENT_STARTED.register(client -> onClientStarted());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> onClientStopping());
		ClientTickEvents.END_CLIENT_TICK.register(BattleMusicClient::onEndClientTick);

		//music mute on l;eave
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onDisconnect());
	}
	//?}

	/**
	 * Loader-neutral startup: config, music library, audio engine, state
	 * machine, and the secret features. Called once from the Fabric entrypoint
	 * or the Forge/NeoForge mod constructors (client dist only).
	 */
	public static void init() {
		config = BattleMusicConfig.load();
		//dir regular/heavy battle
		library = new MusicLibrary(Platform.gameDir().resolve(MOD_ID));
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

	/** The client finished starting up -> bring up the OpenAL audio engine. */
	public static void onClientStarted() {
		if (audioEngine != null) audioEngine.init();
	}

	/** The client is shutting down -> stop music and release OpenAL resources. */
	public static void onClientStopping() {
		if (stateMachine != null) stateMachine.reset();
		if (audioEngine != null) audioEngine.shutdown();
	}

	/** End of every client tick -> drive the battle state machine. */
	public static void onEndClientTick(Minecraft client) {
		if (stateMachine != null) stateMachine.onClientTick(client);
	}

	/** Left a world/server -> stop the music immediately. */
	public static void onDisconnect() {
		if (stateMachine != null) stateMachine.reset();
	}


	// Debug-level logging gated by config.debug
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
