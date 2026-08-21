package me.lemon553311.battlemusic.audio;

import me.lemon553311.battlemusic.BattleMusicClient;

import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.lwjgl.stb.STBVorbis.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * One playback voice: streams an ogg on a daemon thread via STB Vorbis +
 * javax.sound, independent of Minecraft's OpenAL engine. Gains/fades are
 * advanced by update() on the client tick and only read here.
 */

public class MusicChannel {
	private static final int SAMPLES_PER_CHUNK = 4096; // per channel

	private final AudioEngine engine;
	private final String name;

	private volatile float currentGain = 0f;  // 0..1, pre-master
	private volatile float targetGain = 0f;
	private float fadeRate = 0f;               // gain units per second
	private boolean stopWhenSilent = false;
	private volatile float outputVolume = 1f;
	// folder volume * per-song volume from config
	private volatile float trackGain = 1f;

	private volatile Path loaded;
	private volatile Thread playThread;
	private volatile boolean running = false;
	// per-playback gate so a stale thread can never be revived by a later start()
	private volatile AtomicBoolean activeFlag;
	// sample frame offset of the next sample to decode (for battle resume)
	private volatile long playbackFrame = 0L;

	public MusicChannel(AudioEngine engine, String name) {
		this.engine = engine;
		this.name = name;
	}

    // Load + start from the beginning. Resets gain to 0.
	public boolean start(Path oggPath, boolean loop) {
		return start(oggPath, loop, 0L, 0.0);
	}

	// startFrame = sample offset to resume from (battle resume)
	public boolean start(Path oggPath, boolean loop, long startFrame) {
		return start(oggPath, loop, startFrame, 0.0);
	}

	// startSeconds: begin N seconds in (per-song "start at"), ignored when
	// resuming from a frame
	public boolean start(Path oggPath, boolean loop, long startFrame, double startSeconds) {

		if (!engine.isReady() || oggPath == null) return false;
		if (!Files.isReadable(oggPath)) {
			BattleMusicClient.LOGGER.warn("[{}] cannot read {}", name, oggPath);
			return false;

		}
		if (!MusicLibrary.isPlayable(oggPath)) {
			BattleMusicClient.debug("[{}] skipping {} (failed to decode earlier this session)", name, oggPath.getFileName());
			return false;
		}

		stopThread();
		currentGain = 0f;
		playbackFrame = Math.max(0L, startFrame);
		final AtomicBoolean myFlag = new AtomicBoolean(true);
		activeFlag = myFlag;
		running = true;
		loaded = oggPath;
		final Path path = oggPath;
		final long seekTo = Math.max(0L, startFrame);
		final double seekSeconds = Math.max(0.0, startSeconds);
		Thread t = new Thread(() -> streamLoop(path, loop, seekTo, seekSeconds, myFlag), "battlemusic-" + name);
		t.setDaemon(true);
		playThread = t;
		t.start();
		BattleMusicClient.debug("[{}] start: {} (loop={}, startFrame={})", name, oggPath.getFileName(), loop, seekTo);
		return true;
	}

	//Beginning fade-forward
	public void fadeTo(float target, double seconds, boolean stopAtZero) {

		target = Math.max(0f, Math.min(1f, target));
		this.targetGain = target;
		this.stopWhenSilent = stopAtZero && target <= 0f;
		this.fadeRate = (seconds <= 0.0) ? Float.MAX_VALUE : (float) (1.0 / seconds);
	}

	//INSTANT MUTE
	public void hardStop() {

		targetGain = 0f;
		currentGain = 0f;
		stopWhenSilent = false;
		stopThread();
	}

	public void setOutputVolume(float volume) {
		this.outputVolume = Math.max(0f, Math.min(1f, volume));
	}

	// per-track gain, may exceed 1 to boost a quiet track (clamped per sample)
	public void setTrackGain(float gain) {
		this.trackGain = Math.max(0f, gain);
	}

	// advance the fade, once per tick
	public void update(double dtSeconds) {
		if (currentGain != targetGain) {
			float step = (float) (fadeRate * dtSeconds);
			if (currentGain < targetGain) {
				currentGain = Math.min(targetGain, currentGain + step);
			} else {
				currentGain = Math.max(targetGain, currentGain - step);
			}
		}
		if (stopWhenSilent && currentGain <= 0.0001f) {
			stopThread();
			stopWhenSilent = false;
		}
	}

	public boolean isAudible() {
		return currentGain > 0.0001f;
	}
	// Current pre-master gain (0..1), for debug logging.
	public float getCurrentGain() {
		return currentGain;
	}
	// where the fade is heading, so the state machine doesn't re-fire a fade-in
	// that's already running
	public float getTargetGain() {
		return targetGain;
	}
	public boolean isLoaded() {
		return loaded != null && running;
	}
	public Path getLoaded() {
		return loaded;
	}
	public long getPlaybackFrame() {
		return playbackFrame;
	}
	// true once the playback thread stopped (e.g. a non-looping track ended)
	public boolean isFinished() {

		Thread t = playThread;
		return t == null || !t.isAlive();
	}

	public void release() {
		stopThread();
	}

	// Internals

	private void stopThread() {
		running = false;
		AtomicBoolean f = activeFlag;
		if (f != null) f.set(false); // trip THIS playback gate so it exits for good
		activeFlag = null;
		Thread t = playThread;
		playThread = null;
		loaded = null;

		if (t != null && t != Thread.currentThread()) {
			BattleMusicClient.debug("[{}] stopping playback thread", name);
			t.interrupt(); // wake it if blocked, the gate above guarantees it cannot resume
		}
	}	private void streamLoop(Path path, boolean loop, long startFrame, double startSeconds, AtomicBoolean alive) {
		long decoder = NULL;
		SourceDataLine line = null;
		ShortBuffer pcm = null;
		ByteBuffer encoded = null;

		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer error = stack.mallocInt(1);
			// read the file and decode from memory instead of stb_vorbis_open_filename:
			// stb opens paths with C fopen(), which on Windows uses the legacy ANSI
			// codepage, so any non-ASCII track/user name failed to open
			byte[] fileBytes = Files.readAllBytes(path);
			encoded = MemoryUtil.memAlloc(fileBytes.length);
			encoded.put(fileBytes);
			encoded.flip();
			decoder = stb_vorbis_open_memory(encoded, error, null);

			if (decoder == NULL) {
				BattleMusicClient.LOGGER.warn("[{}] STB open failed for {} (err {})", name, path, error.get(0));
				warnUnplayable(path, fileBytes);
				return;
			}

			int channels;
			int sampleRate;
			STBVorbisInfo info = STBVorbisInfo.malloc();
			try {
				stb_vorbis_get_info(decoder, info);
				channels = info.channels();
				sampleRate = info.sample_rate();
			} finally {
				info.free();
			}

			if (channels < 1 || channels > 2) {
				BattleMusicClient.LOGGER.warn("[{}] unsupported channel count {} in {} (only mono/stereo Ogg Vorbis is supported)", name, channels, path);
				MusicLibrary.markUnplayable(path);
				return;
			}

			// resume uses a frame offset; "start at" uses seconds. a resume wins.
			long seekFrame = startFrame;
			if (seekFrame <= 0L && startSeconds > 0.0) {
				seekFrame = (long) (startSeconds * sampleRate);
			}
			// guard playbackFrame writes with alive.get(): a stale thread losing the
			// stopThread() race can still be blocked in line.write() for a bit, and an
			// unguarded write would clobber the NEW track's resume position
			if (seekFrame > 0L) {
				if (!stb_vorbis_seek(decoder, (int) seekFrame)) {
					BattleMusicClient.debug("[{}] seek to frame {} failed; starting from 0", name, seekFrame);
					stb_vorbis_seek_start(decoder);
					if (alive.get()) playbackFrame = 0L;
				} else {
					if (alive.get()) playbackFrame = seekFrame;
					BattleMusicClient.debug("[{}] starting at frame {}", name, seekFrame);
				}
			}

			AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false); // signed, little-endian
			DataLine.Info dlInfo = new DataLine.Info(SourceDataLine.class, format);
			line = (SourceDataLine) AudioSystem.getLine(dlInfo);
			// small explicit buffer (~2 chunks): the default is often ~0.5s+, and since
			// gain is baked in at write time, fades would lag by the whole buffer
			line.open(format, SAMPLES_PER_CHUNK * channels * 2 * 2);
			line.start();
			BattleMusicClient.debug("[{}] playing {} @ {} Hz, {} ch", name, path.getFileName(), sampleRate, channels);

			pcm = MemoryUtil.memAllocShort(SAMPLES_PER_CHUNK * channels);
			byte[] bytes = new byte[SAMPLES_PER_CHUNK * channels * 2];
			boolean justLooped = false;

			while (alive.get() && !Thread.currentThread().isInterrupted()) {
				pcm.clear();
				int n = stb_vorbis_get_samples_short_interleaved(decoder, channels, pcm);

				if (n <= 0) {
					if (loop && !justLooped) {
						BattleMusicClient.debug("[{}] looping {}", name, path.getFileName());
						stb_vorbis_seek_start(decoder);
						if (alive.get()) playbackFrame = 0L;
						justLooped = true;
						continue;
					}
					if (loop) {
						// restarted but still no samples: broken file, bail instead of
						// spinning at 100% CPU forever
						BattleMusicClient.LOGGER.warn("[{}] {} produced no samples after a loop restart; stopping playback", name, path.getFileName());
						MusicLibrary.markUnplayable(path);
					} else {
						BattleMusicClient.debug("[{}] reached end of {}", name, path.getFileName());
					}
					break;
				}
				justLooped = false;

				if (alive.get()) playbackFrame = stb_vorbis_get_sample_offset(decoder);
				int sampleCount = n * channels;
				float gain = currentGain * outputVolume * trackGain;
				if (gain < 0f) gain = 0f;

				for (int i = 0; i < sampleCount; i++) {
					int v = (int) (pcm.get(i) * gain);
					if (v > 32767) v = 32767;
					else if (v < -32768) v = -32768;
					bytes[i * 2] = (byte) (v & 0xFF);
					bytes[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
				}
				line.write(bytes, 0, sampleCount * 2);
			}

		} catch (Throwable t) {
			BattleMusicClient.LOGGER.warn("[{}] playback error for {}", name, path, t);
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
			// only the still-current playback may clear shared status
			if (activeFlag == alive) {
				running = false;
			}
		}
	}

	// decode-failure diagnostics. most common real-world cause: an "ogg" that is
	// actually Ogg OPUS (youtube rippers produce these). reported once per file
	// per session; the blacklist keeps the picker off the file afterwards.
	private void warnUnplayable(Path path, byte[] fileBytes) {
		if (!MusicLibrary.markUnplayable(path)) return; // already reported this session
		if (looksLikeOpus(fileBytes)) {
			BattleMusicClient.LOGGER.warn(
					"[{}] '{}' is an Ogg OPUS file, not Ogg VORBIS, so it cannot be played. "
							+ "Re-encode it, e.g.: ffmpeg -i \"{}\" -c:a libvorbis \"{}\" . "
							+ "Skipping this file until the music folder changes.",
					name, path.getFileName(), path.getFileName(),
					path.getFileName().toString().replaceFirst("\\.ogg$", "") + "-vorbis.ogg");
		} else {
			BattleMusicClient.LOGGER.warn(
					"[{}] '{}' could not be decoded as Ogg Vorbis (corrupt file, or another format renamed to .ogg?). "
							+ "Skipping this file until the music folder changes.",
					name, path.getFileName());
		}
	}

	private static boolean looksLikeOpus(byte[] bytes) {
		// OpusHead magic in the first Ogg page
		int limit = Math.min(bytes.length, 512) - 8;
		for (int i = 0; i <= limit; i++) {
			if (bytes[i] == 'O' && bytes[i + 1] == 'p' && bytes[i + 2] == 'u' && bytes[i + 3] == 's'
					&& bytes[i + 4] == 'H' && bytes[i + 5] == 'e' && bytes[i + 6] == 'a' && bytes[i + 7] == 'd') {
				return true;
			}
		}
		return false;
	}
}
