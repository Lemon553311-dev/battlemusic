package me.lemon553311.battlemusic.audio;

import me.lemon553311.battlemusic.BattleMusicClient;

/**
 * Audio is played through the Java Sound API (javax.sound.sampled) on dedicated
 * per-channel background threads, completely independent of Minecraft's OpenAL
 * sound engine.
 *
 * <p>Java Sound ships with the JRE, so there is nothing to allocate up front;
 * lines are opened lazily per track on the playback threads.
 */
public class AudioEngine {
	private volatile boolean ready = false;
	// Nothing here can fail: Java Sound ships with the JRE and lines are opened
	// lazily per track, so init only marks the engine usable.
	public synchronized void init() {
		ready = true;
		BattleMusicClient.LOGGER.info("Battle music audio engine ready (Java Sound, independent of Minecraft audio)");
	}

	public synchronized void shutdown() {
		ready = false;
		BattleMusicClient.debug("Audio engine shutdown");
	}

	public boolean isReady() {
		return ready;
	}
}