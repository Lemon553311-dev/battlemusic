package me.lemon553311.battlemusic.audio;

import me.lemon553311.battlemusic.BattleMusicClient;

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
 * @author user2378
 *
 * One playback voice. Streams an Ogg Vorbis file on its own daemon thread: it
 * decodes a small chunk at a time with STB Vorbis (the decoder LWJGL/Minecraft
 * already ships), scales the samples by the current (faded) gain, and writes
 * them to a Java Sound {@link SourceDataLine}. Writing to the line blocks until
 * the buffer has room, which paces playback in real time with no busy-loop.
 *
 * Nothing here touches Minecraft's OpenAL sound engine, and no decoding happens
 * on the render thread, so there is no audio-context conflict and no freezing.
 *
 * The public API is identical to the previous OpenAL version, so the state
 * machine does not change: gain/fades are advanced by {@link #update(double)}
 * on the client tick thread (cheap float math) and merely read by the playback
 * thread.
 */

public class MusicChannel {
	private static final int SAMPLES_PER_CHUNK = 4096; // per channel

	private final AudioEngine engine;
	private final String name;

	private volatile float currentGain = 0f;  // 0..1, pre-master
	private volatile float targetGain = 0f;    // 0..1, pre-master
	private float fadeRate = 0f;               // gain units per second
	private boolean stopWhenSilent = false;
	private volatile float outputVolume = 1f;

	private volatile Path loaded;
	private volatile Thread playThread;
	private volatile boolean running = false;
	// per-playback run gate. each start() gets its own flag, stopThread() trips it.
	// a fresh thread never shares a flag with an old one, so a stale thread always
	// dies and can't be revived by a later start. kills the rare double-playback.
	private volatile AtomicBoolean activeFlag;
	// Per-channel sample-frame offset of the next sample to decode, updated as
	// playback advances. Used by the "battle resume" feature to pick up where a
	// track faded out instead of restarting from the beginning
	private volatile long playbackFrame = 0L;

	public MusicChannel(AudioEngine engine, String name) {
		this.engine = engine;
		this.name = name;
	}

    // Load + start from the beginning. Resets gain to 0.
	public boolean start(Path oggPath, boolean loop) {
		return start(oggPath, loop, 0L);
	}

	/**
	 * Load + start a track, beginning playback at {@code startFrame} (a per-channel
	 * sample offset; 0 = from the start). Resets gain to 0 so the caller can fade
	 * it in. Used by "battle resume" to continue a track where it left off.
	 */
	public boolean start(Path oggPath, boolean loop, long startFrame) {

		if (!engine.isReady() || oggPath == null) return false;
		if (!Files.isReadable(oggPath)) {
			BattleMusicClient.LOGGER.warn("[{}] cannot read {}", name, oggPath);
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
		Thread t = new Thread(() -> streamLoop(path, loop, seekTo, myFlag), "battlemusic-" + name);
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

	// Advance the fade. Call once per tick with real dt. Cheap, no audio.
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
	// where the fade is heading (0..1). lets the state machine check if the channel
	// got pushed down vs already heading up, so it doesn't re-fire a fade-in every
	// tick and stomp the continuation fade.
	public float getTargetGain() {
		return targetGain;
	}
	public boolean isLoaded() {
		return loaded != null && running;
	}
	public Path getLoaded() {
		return loaded;
	}
	// Per-channel sample-frame offset currently being played (for battle resume)
	public long getPlaybackFrame() {
		return playbackFrame;
	}
	// True once the playback thread has stopped (e.g. a non-looping track ended)
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
	}

	private void streamLoop(Path path, boolean loop, long startFrame, AtomicBoolean alive) {
		long decoder = NULL;
		SourceDataLine line = null;
		ShortBuffer pcm = null;

		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer error = stack.mallocInt(1);
			decoder = stb_vorbis_open_filename(path.toAbsolutePath().toString(), error, null);

			if (decoder == NULL) {
				BattleMusicClient.LOGGER.warn("[{}] STB open failed for {} (err {})", name, path, error.get(0));
				return;
			}

			int channels;
			int sampleRate;
			STBVorbisInfo info = STBVorbisInfo.malloc(stack);
			stb_vorbis_get_info(decoder, info);
			channels = info.channels();
			sampleRate = info.sample_rate();

			if (channels < 1 || channels > 2) {
				BattleMusicClient.LOGGER.warn("[{}] unsupported channel count {} in {}", name, channels, path);
				return;
			}

            // Resume playback partway through the track. If the seek fails (e.g.
            // the file changed length), fall back to the start
			if (startFrame > 0L) {
				if (!stb_vorbis_seek(decoder, (int) startFrame)) {
					BattleMusicClient.debug("[{}] seek to frame {} failed; starting from 0", name, startFrame);
					stb_vorbis_seek_start(decoder);
					playbackFrame = 0L;
				} else {
					playbackFrame = startFrame;
					BattleMusicClient.debug("[{}] resumed at frame {}", name, startFrame);
				}
			}

			AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false); // signed, little-endian
			DataLine.Info dlInfo = new DataLine.Info(SourceDataLine.class, format);
			line = (SourceDataLine) AudioSystem.getLine(dlInfo);
			line.open(format);
			line.start();
			BattleMusicClient.debug("[{}] playing {} @ {} Hz, {} ch", name, path.getFileName(), sampleRate, channels);

			pcm = MemoryUtil.memAllocShort(SAMPLES_PER_CHUNK * channels);
			byte[] bytes = new byte[SAMPLES_PER_CHUNK * channels * 2];

			while (alive.get() && !Thread.currentThread().isInterrupted()) {
				pcm.clear();
				int n = stb_vorbis_get_samples_short_interleaved(decoder, channels, pcm);

				if (n <= 0) {
					if (loop) {
						BattleMusicClient.debug("[{}] looping {}", name, path.getFileName());
						stb_vorbis_seek_start(decoder);
						playbackFrame = 0L;
						continue;
					}
					BattleMusicClient.debug("[{}] reached end of {}", name, path.getFileName());
					break;
				}

				playbackFrame = stb_vorbis_get_sample_offset(decoder);
				int sampleCount = n * channels;
				float gain = clamp01(currentGain * outputVolume);

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
			// Only the still-current playback may clear shared status, so a stale
			// thread finishing never clobbers a freshly started one
			if (activeFlag == alive) {
				running = false;
			}
		}
	}

	private static float clamp01(float g) {
		return g < 0f ? 0f : (g > 1f ? 1f : g);
	}
}
