package me.lemon553311.battlemusic.audio;

import me.lemon553311.battlemusic.BattleMusicClient;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;

import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.lwjgl.stb.STBVorbis.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Self-contained .ogg preview player for the mod-menu "Songs" tab.
 *
 * Reuses the exact decode/playback approach proven in {@link MusicChannel} and
 * {@link me.lemon553311.battlemusic.lasttotem.OneShotSound} (STB Vorbis -> Java
 * Sound on a daemon thread), so it is independent of Minecraft's OpenAL engine
 * and the battle state machine. Only ONE preview plays at a time: starting a new
 * one stops the previous, and {@link #stop()} cuts it immediately.
 *
 * The preview plays at the track's configured volume (folder volume * per-song
 * volume, passed in by the caller) scaled by the current MC master*music volume,
 * and from the per-song "start at" offset, so what you hear matches what a real
 * battle would play.
 */
public final class PreviewPlayer {
	private PreviewPlayer() {}

	private static final int SAMPLES_PER_CHUNK = 4096; // per channel

	// The currently playing preview's run gate + thread, so a new preview (or a
	// stop) can cancel the old one cleanly.
	private static volatile AtomicBoolean current;
	private static volatile Thread currentThread;

	/** Stop any preview that is currently playing. Safe to call when nothing plays. */
	public static synchronized void stop() {
		AtomicBoolean c = current;
		if (c != null) c.set(false);
		Thread t = currentThread;
		if (t != null) t.interrupt();
		current = null;
		currentThread = null;
	}

	/**
	 * Play {@code ogg} immediately at {@code configGain} (folder*song volume) from
	 * {@code startSeconds} into the track, on its own daemon thread. Any previous
	 * preview is stopped first.
	 */
	public static synchronized void play(Path ogg, float configGain, double startSeconds) {
		stop();
		if (ogg == null || !Files.isReadable(ogg)) {
			BattleMusicClient.LOGGER.warn("[preview] cannot read {}", ogg);
			return;
		}
		final AtomicBoolean alive = new AtomicBoolean(true);
		current = alive;
		final float g = Math.max(0f, configGain);
		final double startSec = Math.max(0.0, startSeconds);
		Thread t = new Thread(() -> stream(ogg, g, startSec, alive), "battlemusic-preview");
		t.setDaemon(true);
		currentThread = t;
		t.start();
		BattleMusicClient.debug("[preview] playing {} (gain={}, startSeconds={})", ogg.getFileName(), g, startSec);
	}

	private static float masterVolume() {
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc == null || mc.options == null) return 1f;
			return mc.options.getSoundSourceVolume(SoundSource.MASTER)
					* mc.options.getSoundSourceVolume(SoundSource.MUSIC);
		} catch (Throwable t) {
			return 1f;
		}
	}

	private static void stream(Path path, float configGain, double startSeconds, AtomicBoolean alive) {
		long decoder = NULL;
		SourceDataLine line = null;
		ShortBuffer pcm = null;

		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer error = stack.mallocInt(1);
			decoder = stb_vorbis_open_filename(path.toAbsolutePath().toString(), error, null);
			if (decoder == NULL) {
				BattleMusicClient.LOGGER.warn("[preview] STB open failed for {} (err {})", path, error.get(0));
				return;
			}

			STBVorbisInfo info = STBVorbisInfo.malloc(stack);
			stb_vorbis_get_info(decoder, info);
			int channels = info.channels();
			int sampleRate = info.sample_rate();
			if (channels < 1 || channels > 2) {
				BattleMusicClient.LOGGER.warn("[preview] unsupported channel count {} in {}", channels, path);
				return;
			}

			if (startSeconds > 0.0) {
				int seek = (int) (startSeconds * sampleRate);
				if (seek > 0 && !stb_vorbis_seek(decoder, seek)) {
					stb_vorbis_seek_start(decoder);
				}
			}

			AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false); // signed, little-endian
			DataLine.Info dlInfo = new DataLine.Info(SourceDataLine.class, format);
			line = (SourceDataLine) AudioSystem.getLine(dlInfo);
			line.open(format);
			line.start();

			float gain = configGain * masterVolume();
			if (gain < 0f) gain = 0f;

			pcm = MemoryUtil.memAllocShort(SAMPLES_PER_CHUNK * channels);
			byte[] bytes = new byte[SAMPLES_PER_CHUNK * channels * 2];

			while (alive.get() && !Thread.currentThread().isInterrupted()) {
				pcm.clear();
				int n = stb_vorbis_get_samples_short_interleaved(decoder, channels, pcm);
				if (n <= 0) break; // reached the end -> preview finished

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
			if (alive.get()) line.drain();

		} catch (Throwable t) {
			BattleMusicClient.LOGGER.warn("[preview] playback error for {}", path, t);
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
