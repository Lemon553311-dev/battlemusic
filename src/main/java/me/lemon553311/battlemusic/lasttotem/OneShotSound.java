package me.lemon553311.battlemusic.lasttotem;

import me.lemon553311.battlemusic.BattleMusicClient;

import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.ConcurrentHashMap;

import static org.lwjgl.stb.STBVorbis.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Fire-and-forget one-shot ogg player for the secret alerts. Same approach as
 * MusicChannel (stb + javax.sound on a daemon thread), no OpenAL involvement.
 * The bundled ogg is decoded from memory: stb_vorbis_open_filename goes through
 * C fopen(), which breaks on non-ASCII Windows user names.
 */
public final class OneShotSound {
	private OneShotSound() {}

	private static final int SAMPLES_PER_CHUNK = 4096; // per channel
	private static final String RESOURCE = "/assets/battlemusic/lts/LRS_StartSound.ogg";

	// in-memory copies of the bundled oggs, read from the jar once
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
				// readAllBytes is Java 9+; 1.16.5 builds on Java 8
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
		long decoder = NULL;
		SourceDataLine line = null;
		ShortBuffer pcm = null;
		ByteBuffer encoded = null;

		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer error = stack.mallocInt(1);
			encoded = MemoryUtil.memAlloc(data.length);
			encoded.put(data);
			encoded.flip();
			decoder = stb_vorbis_open_memory(encoded, error, null);
			if (decoder == NULL) {
				BattleMusicClient.LOGGER.warn("[lts] STB open failed for {} (err {})", label, error.get(0));
				return;
			}

			STBVorbisInfo info = STBVorbisInfo.malloc();
			int channels;
			int sampleRate;
			try {
				stb_vorbis_get_info(decoder, info);
				channels = info.channels();
				sampleRate = info.sample_rate();
			} finally {
				info.free();
			}
			if (channels < 1 || channels > 2) {
				BattleMusicClient.LOGGER.warn("[lts] unsupported channel count {} in {}", channels, label);
				return;
			}

			AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false); // signed, little-endian
			DataLine.Info dlInfo = new DataLine.Info(SourceDataLine.class, format);
			line = (SourceDataLine) AudioSystem.getLine(dlInfo);
			line.open(format);
			line.start();

			pcm = MemoryUtil.memAllocShort(SAMPLES_PER_CHUNK * channels);
			byte[] bytes = new byte[SAMPLES_PER_CHUNK * channels * 2];

			while (!Thread.currentThread().isInterrupted()) {
				pcm.clear();
				int n = stb_vorbis_get_samples_short_interleaved(decoder, channels, pcm);
				if (n <= 0) break; // reached the end -> one-shot finished

				int sampleCount = n * channels;
				for (int i = 0; i < sampleCount; i++) {
					int v = (int) (pcm.get(i) * gain);
					if (v > 32767) v = 32767;
					else if (v < -32768) v = -32768;
					bytes[i * 2] = (byte) (v & 0xFF);
					bytes[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
				}
				line.write(bytes, 0, sampleCount * 2);
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
			if (decoder != NULL) {
				try { stb_vorbis_close(decoder); } catch (Throwable ignored) {}
			}
			if (encoded != null) {
				// freed after the decoder: stb reads from it for its whole lifetime
				try { MemoryUtil.memFree(encoded); } catch (Throwable ignored) {}
			}
		}
	}
}
