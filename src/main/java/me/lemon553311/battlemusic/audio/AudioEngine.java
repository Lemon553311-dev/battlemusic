package me.lemon553311.battlemusic.audio;

import me.lemon553311.battlemusic.BattleMusicClient;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;

//java Sound ships with the JRE, nothing to allocate up front
//lines are opened lazily per track on the playback threads

public class AudioEngine {
	private volatile boolean ready = false;
	public synchronized void init() {
		try {
			// probe for a usable output instead of assuming one exists: with no
			// SourceDataLine at all, every track start would fail on its playback
			// thread and the state machine would keep re-rolling tracks against the
			// same dead output (a thread + a logged stack trace per attempt).
			boolean found = false;
			for (Mixer.Info info : AudioSystem.getMixerInfo()) {
				try {
					if (AudioSystem.getMixer(info).getSourceLineInfo().length > 0) {
						found = true;
						break;
					}
				} catch (Throwable ignored) {
					// a mixer that cannot be queried simply does not count
				}
			}
			ready = found;
			if (ready) {
				BattleMusicClient.LOGGER.info("Battle music audio engine ready (Java Sound, independent of Minecraft audio)");
			} else {
				BattleMusicClient.LOGGER.warn("No Java Sound output line available; battle music disabled for this session");
			}
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
