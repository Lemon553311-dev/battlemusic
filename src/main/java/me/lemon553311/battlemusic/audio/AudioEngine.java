package me.lemon553311.battlemusic.audio;

import me.lemon553311.battlemusic.BattleMusicClient;

/**
 * @author Lemon553311, user2378
 *
 * Audio is played through the Java Sound API (javax.sound.sampled) on dedicated
 * per-channel background threads, completely independent of Minecraft's OpenAL
 * sound engine.
 */

//java Sound ships with the JRE, nothing to allocate up front
//lines are opened lazily per track on the playback threads

public class AudioEngine {
	private volatile boolean ready = false;
	public synchronized void init() {
		try {
			ready = true;
			BattleMusicClient.LOGGER.info("Battle music audio engine ready (Java Sound, independent of Minecraft audio)");
		} catch (Throwable t) {
			ready = false;
			BattleMusicClient.LOGGER.error("Audio engine init failed; battle music disabled", t);
		}
	}

	public synchronized void shutdown() {
		ready = false;
		BattleMusicClient.debug("Audio engine shutdown");
	}

	public boolean isReady() {
		return ready;
	}
}