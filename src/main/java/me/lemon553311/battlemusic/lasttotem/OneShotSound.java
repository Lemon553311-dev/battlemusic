package me.lemon553311.battlemusic.lasttotem;

import me.lemon553311.battlemusic.BattleMusicClient;
import me.lemon553311.battlemusic.audio.OggPlayer;

import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ShortBuffer;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fire-and-forget one-shot Ogg Vorbis player for the alert sound effects.
 *
 * Uses the same {@link OggPlayer} decode loop as {@link MusicChannel} but
 * without fades or looping: it plays the bundled .ogg once at a fixed gain
 * and stops.
 *
 * The bundled .ogg is read from the jar into memory once per resource path
 * (cached) and decoded from memory via stb_vorbis_open_memory. This avoids
 * C fopen() path issues on non-ASCII Windows user names.
 */
public final class OneShotSound {
	private OneShotSound() {}

	private static final String RESOURCE = "/assets/battlemusic/lts/LRS_StartSound.ogg";
	private static final ConcurrentHashMap<String, byte[]> LOADED = new ConcurrentHashMap<>();

	public static void play(float gain) {
		play(RESOURCE, gain);
	}

	public static void play(String resource, float gain) {
		final float g = Math.max(0f, Math.min(1f, gain));
		Thread t = new Thread(() -> {
			try {
				byte[] data = ensureLoaded(resource);
				if (data != null) stream(resource, data, g);
			} catch (Throwable th) {
				BattleMusicClient.LOGGER.warn("[lts] one-shot sound failed for {}", resource, th);
			}
		}, "battlemusic-oneshot");
		t.setDaemon(true);
		t.start();
	}

	private static byte[] ensureLoaded(String resource) throws Exception {
		byte[] cached = LOADED.get(resource);
		if (cached != null) return cached;
		synchronized (OneShotSound.class) {
			cached = LOADED.get(resource);
			if (cached != null) return cached;
			try (InputStream in = OneShotSound.class.getResourceAsStream(resource)) {
				if (in == null) {
					BattleMusicClient.LOGGER.warn("[lts] bundled sound not found on classpath at {}", resource);
					return null;
				}
				ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
				byte[] buf = new byte[8192];
				int n;
				while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
				byte[] data = out.toByteArray();
				LOADED.put(resource, data);
				return data;
			}
		}
	}

	private static void stream(String label, byte[] data, float gain) {
		boolean ok = OggPlayer.play(data,
			(format, line) -> line.open(format),
			new OggPlayer.Callbacks() {
				@Override
				public boolean onSamples(ShortBuffer p, int n, int channels, byte[] bytes, SourceDataLine l, long d) {
					int sampleCount = n * channels;
					float g = Math.max(0f, gain);
					for (int i = 0; i < sampleCount; i++) {
						int v = (int) (p.get(i) * g);
						if (v > 32767) v = 32767;
						else if (v < -32768) v = -32768;
						bytes[i * 2] = (byte) (v & 0xFF);
						bytes[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
					}
					l.write(bytes, 0, sampleCount * 2);
					return !Thread.currentThread().isInterrupted();
				}
			});
		if (!ok) {
			BattleMusicClient.LOGGER.warn("[lts] playback failed for {}", label);
		}
	}
}
