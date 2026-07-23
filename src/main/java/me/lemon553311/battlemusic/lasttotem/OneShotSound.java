package me.lemon553311.battlemusic.lasttotem;

import me.lemon553311.battlemusic.BattleMusicClient;
import me.lemon553311.battlemusic.audio.VorbisStream;

import org.lwjgl.system.MemoryUtil;

import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ShortBuffer;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fire-and-forget one-shot Ogg Vorbis player for the Last Totem Standing alert.
 *
 * This shares the decode/playback plumbing proven in
 * {@link me.lemon553311.battlemusic.audio.MusicChannel} (STB Vorbis -> Java
 * Sound on a daemon thread, via {@link me.lemon553311.battlemusic.audio.VorbisStream}),
 * so it does not depend on Minecraft's OpenAL sound engine or its sound-event
 * registry at all.
 *
 * The bundled .ogg is read from the jar into memory once and decoded with
 * stb_vorbis_open_memory. It is deliberately NOT extracted to a temp file:
 * stb_vorbis_open_filename goes through C fopen(), which on Windows expects
 * the legacy ANSI codepage, so a non-ASCII Windows user name (e.g. a Cyrillic
 * account -> C:\Users\&lt;name&gt;\AppData\Local\Temp\...) made the alert fail
 * to open silently. Decoding from memory has no file path to break.
 */
public final class OneShotSound {
	private OneShotSound() {}

	private static final int SAMPLES_PER_CHUNK = 4096; // per channel
	// Default alert used by the no-resource play(gain) overload (Last Totem Standing).
	private static final String RESOURCE = "/assets/battlemusic/lts/LRS_StartSound.ogg";

	// In-memory copies of the bundled oggs, keyed by classpath resource path.
	// Each distinct sound is read from the jar once (they are small).
	private static final ConcurrentHashMap<String, byte[]> LOADED = new ConcurrentHashMap<>();

	/** Play the default Last Totem Standing alert once at the given gain (0..1). */
	public static void play(float gain) {
		play(RESOURCE, gain);
	}

	/**
	 * Play a bundled one-shot ogg (by classpath resource path) once at the given
	 * gain (0..1), on its own daemon thread. Lets each secret "Fun" alert ship its
	 * own sound without touching Minecraft's OpenAL sound engine.
	 */
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
				// InputStream#readAllBytes is Java 9+; the 1.16.5 tier builds on Java 8.
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
		VorbisStream vorbis = null;
		SourceDataLine line = null;
		ShortBuffer pcm = null;

		try {
			vorbis = VorbisStream.open(data, "[lts] " + label);
			if (vorbis == null) return;
			int channels = vorbis.channels();
			if (channels < 1 || channels > 2) {
				BattleMusicClient.LOGGER.warn("[lts] unsupported channel count {} in {}", channels, label);
				return;
			}

			// Implementation-default line buffer: one-shots have no fades, so
			// buffer-induced gain latency does not matter here.
			line = vorbis.openLine(0);

			pcm = MemoryUtil.memAllocShort(SAMPLES_PER_CHUNK * channels);
			byte[] bytes = new byte[SAMPLES_PER_CHUNK * channels * 2];

			while (!Thread.currentThread().isInterrupted()) {
				pcm.clear();
				int n = vorbis.read(pcm);
				if (n <= 0) break; // reached the end -> one-shot finished
				line.write(bytes, 0, vorbis.toBytes(pcm, n, gain, bytes));
			}
			line.drain();

		} catch (Throwable t) {
			BattleMusicClient.LOGGER.warn("[lts] playback error for {}", label, t);
		} finally {
			if (pcm != null) {
				try { MemoryUtil.memFree(pcm); } catch (Throwable ignored) {}
			}
			if (line != null) {
				try { line.flush(); line.stop(); line.close(); } catch (Throwable ignored) {}
			}
			// Closes the decoder first, then frees the encoded buffer it reads from.
			if (vorbis != null) vorbis.close();
		}
	}
}
