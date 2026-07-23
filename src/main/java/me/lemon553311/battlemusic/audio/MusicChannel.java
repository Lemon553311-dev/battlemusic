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
 * One playback voice. Streams an Ogg Vorbis file on its own daemon thread using
 * the shared {@link OggPlayer} decode loop, with per-channel gain for fades and
 * a per-track volume (folder volume * per-song volume from config).
 *
 * Nothing here touches Minecraft's OpenAL sound engine, and no decoding happens
 * on the render thread.
 */
public class MusicChannel {
	private static final int SAMPLES_PER_CHUNK = 4096;

	private final AudioEngine engine;
	private final String name;

	private volatile float currentGain = 0f;
	private volatile float targetGain = 0f;
	private float fadeRate = 0f;
	private boolean stopWhenSilent = false;
	private volatile float outputVolume = 1f;
	private volatile float trackGain = 1f;

	private volatile Path loaded;
	private volatile Thread playThread;
	private volatile boolean running = false;
	private volatile AtomicBoolean activeFlag;
	private volatile long playbackFrame = 0L;

	public MusicChannel(AudioEngine engine, String name) {
		this.engine = engine;
		this.name = name;
	}

	public boolean start(Path oggPath, boolean loop) {
		return start(oggPath, loop, 0L, 0.0);
	}

	public boolean start(Path oggPath, boolean loop, long startFrame) {
		return start(oggPath, loop, startFrame, 0.0);
	}

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

	public void fadeTo(float target, double seconds, boolean stopAtZero) {
		target = Math.max(0f, Math.min(1f, target));
		this.targetGain = target;
		this.stopWhenSilent = stopAtZero && target <= 0f;
		this.fadeRate = (seconds <= 0.0) ? Float.MAX_VALUE : (float) (1.0 / seconds);
	}

	public void hardStop() {
		targetGain = 0f;
		currentGain = 0f;
		stopWhenSilent = false;
		stopThread();
	}

	public void setOutputVolume(float volume) {
		this.outputVolume = Math.max(0f, Math.min(1f, volume));
	}

	public void setTrackGain(float gain) {
		this.trackGain = Math.max(0f, gain);
	}

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

	public float getCurrentGain() {
		return currentGain;
	}

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

	public boolean isFinished() {
		Thread t = playThread;
		return t == null || !t.isAlive();
	}

	public void release() {
		stopThread();
	}

	private void stopThread() {
		running = false;
		AtomicBoolean f = activeFlag;
		if (f != null) f.set(false);
		activeFlag = null;
		Thread t = playThread;
		playThread = null;
		loaded = null;

		if (t != null && t != Thread.currentThread()) {
			BattleMusicClient.debug("[{}] stopping playback thread", name);
			t.interrupt();
		}
	}

	private void streamLoop(Path path, boolean loop, long startFrame, double startSeconds, AtomicBoolean alive) {
		try {
			byte[] fileBytes = Files.readAllBytes(path);

			final boolean[] justLooped = { false };

			OggPlayer.play(fileBytes,
				(format, line) -> line.open(format, SAMPLES_PER_CHUNK * format.getChannels() * 2 * 2),
				new OggPlayer.Callbacks() {
					@Override
					public void onOpened(long d, int channels, int sampleRate) {
						long seekFrame = startFrame;
						if (seekFrame <= 0L && startSeconds > 0.0) {
							seekFrame = (long) (startSeconds * sampleRate);
						}
						if (seekFrame > 0L) {
							if (!stb_vorbis_seek(d, (int) seekFrame)) {
								BattleMusicClient.debug("[{}] seek to frame {} failed; starting from 0", name, seekFrame);
								stb_vorbis_seek_start(d);
								if (alive.get()) playbackFrame = 0L;
							} else {
								if (alive.get()) playbackFrame = seekFrame;
								BattleMusicClient.debug("[{}] starting at frame {}", name, seekFrame);
							}
						}
					}

					@Override
					public boolean onSamples(ShortBuffer p, int n, int ch, byte[] bytes, SourceDataLine l, long d) {
						if (alive.get()) playbackFrame = stb_vorbis_get_sample_offset(d);
						float gain = currentGain * outputVolume * trackGain;
						if (gain < 0f) gain = 0f;
						int sampleCount = n * ch;
						for (int i = 0; i < sampleCount; i++) {
							int v = (int) (p.get(i) * gain);
							if (v > 32767) v = 32767;
							else if (v < -32768) v = -32768;
							bytes[i * 2] = (byte) (v & 0xFF);
							bytes[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
						}
						l.write(bytes, 0, sampleCount * 2);
						return alive.get() && !Thread.currentThread().isInterrupted();
					}

					@Override
					public boolean onEndOfStream(long d) {
						if (loop && !justLooped[0]) {
							BattleMusicClient.debug("[{}] looping {}", name, path.getFileName());
							stb_vorbis_seek_start(d);
							if (alive.get()) playbackFrame = 0L;
							justLooped[0] = true;
							return true;
						}
						if (loop) {
							BattleMusicClient.LOGGER.warn("[{}] {} produced no samples after a loop restart; stopping playback", name, path.getFileName());
							MusicLibrary.markUnplayable(path);
						}
						return false;
					}
				});

		} catch (Throwable t) {
			BattleMusicClient.LOGGER.warn("[{}] playback error for {}", name, path, t);
		} finally {
			if (activeFlag == alive) {
				running = false;
			}
		}
	}
}
