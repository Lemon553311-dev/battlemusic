package me.lemon553311.battlemusic.lasttotem;

import me.lemon553311.battlemusic.BattleMusicClient;

import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.InputStream;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentHashMap;

import static org.lwjgl.stb.STBVorbis.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Fire-and-forget one-shot Ogg Vorbis player for the Last Totem Standing alert.
 *
 * This deliberately reuses the exact decode/playback approach proven in
 * {@link me.lemon553311.battlemusic.audio.MusicChannel} (STB Vorbis -> Java
 * Sound on a daemon thread), so it does not depend on Minecraft's OpenAL sound
 * engine or its sound-event registry at all. The bundled .ogg is extracted from
 * the jar to a temp file once, then streamed at a fixed gain until it ends.
 */
public final class OneShotSound {
	private OneShotSound() {}

	private static final int SAMPLES_PER_CHUNK = 4096; // per channel
	// Default alert used by the no-resource play(gain) overload (Last Totem Standing).
	private static final String RESOURCE = "/assets/battlemusic/lts/LRS_StartSound.ogg";

	// On-disk copies of bundled oggs, keyed by classpath resource path (STB needs a
	// filesystem path, not a stream). Each distinct sound is extracted to temp once.
	private static final ConcurrentHashMap<String, Path> EXTRACTED = new ConcurrentHashMap<>();

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
				Path ogg = ensureExtracted(resource);
				if (ogg != null) stream(ogg, g);
			} catch (Throwable th) {
				BattleMusicClient.LOGGER.warn("[lts] one-shot sound failed for {}", resource, th);
			}
		}, "battlemusic-oneshot");
		t.setDaemon(true);
		t.start();
	}

	private static Path ensureExtracted(String resource) throws Exception {
		Path cached = EXTRACTED.get(resource);
		if (cached != null && Files.isReadable(cached)) return cached;
		synchronized (OneShotSound.class) {
			cached = EXTRACTED.get(resource);
			if (cached != null && Files.isReadable(cached)) return cached;
			try (InputStream in = OneShotSound.class.getResourceAsStream(resource)) {
				if (in == null) {
					BattleMusicClient.LOGGER.warn("[lts] bundled sound not found on classpath at {}", resource);
					return null;
				}
				Path tmp = Files.createTempFile("battlemusic-oneshot-", ".ogg");
				Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
				tmp.toFile().deleteOnExit();
				EXTRACTED.put(resource, tmp);
				return tmp;
			}
		}
	}

	private static void stream(Path path, float gain) {
		long decoder = NULL;
		SourceDataLine line = null;
		ShortBuffer pcm = null;

		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer error = stack.mallocInt(1);
			decoder = stb_vorbis_open_filename(path.toAbsolutePath().toString(), error, null);
			if (decoder == NULL) {
				BattleMusicClient.LOGGER.warn("[lts] STB open failed for {} (err {})", path, error.get(0));
				return;
			}

			STBVorbisInfo info = STBVorbisInfo.malloc(stack);
			stb_vorbis_get_info(decoder, info);
			int channels = info.channels();
			int sampleRate = info.sample_rate();
			if (channels < 1 || channels > 2) {
				BattleMusicClient.LOGGER.warn("[lts] unsupported channel count {} in {}", channels, path);
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
			BattleMusicClient.LOGGER.warn("[lts] playback error for {}", path, t);
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
		}
	}
}
