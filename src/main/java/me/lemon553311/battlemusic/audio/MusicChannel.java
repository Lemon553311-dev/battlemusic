package me.lemon553311.battlemusic.audio;

import me.lemon553311.battlemusic.BattleMusicClient;

import org.lwjgl.system.MemoryUtil;

import javax.sound.sampled.SourceDataLine;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
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
	// Per-track gain (folder volume * per-song volume from config; 1 = unchanged).
	private volatile float trackGain = 1f;

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
		return start(oggPath, loop, 0L, 0.0);
	}

	/**
	 * Load + start a track, beginning playback at {@code startFrame} (a per-channel
	 * sample offset; 0 = from the start). Resets gain to 0 so the caller can fade
	 * it in. Used by "battle resume" to continue a track where it left off.
	 */
	public boolean start(Path oggPath, boolean loop, long startFrame) {
		return start(oggPath, loop, startFrame, 0.0);
	}

	/**
	 * Same as above but, on a fresh (non-resume) start, begins playback
	 * {@code startSeconds} into the track (the per-song "start at" setting).
	 * Ignored when {@code startFrame > 0} so a battle resume always wins.
	 */
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

	// Per-track gain (folder volume * per-song volume). May exceed 1 to boost a
	// quiet track; the per-sample write below clamps to the 16-bit range.
	public void setTrackGain(float gain) {
		this.trackGain = Math.max(0f, gain);
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

	private void streamLoop(Path path, boolean loop, long startFrame, double startSeconds, AtomicBoolean alive) {
		VorbisStream vorbis = null;
		SourceDataLine line = null;
		ShortBuffer pcm = null;

		try {
			// Decode from an in-memory copy of the file; see VorbisStream for why
			// the OS path layer must be bypassed (non-ASCII paths broke fopen()).
			byte[] fileBytes = Files.readAllBytes(path);
			vorbis = VorbisStream.open(fileBytes, "[" + name + "] " + path);
			if (vorbis == null) {
				warnUnplayable(path, fileBytes);
				return;
			}

			int channels = vorbis.channels();
			int sampleRate = vorbis.sampleRate();

			if (channels < 1 || channels > 2) {
				BattleMusicClient.LOGGER.warn("[{}] unsupported channel count {} in {} (only mono/stereo Ogg Vorbis is supported)", name, channels, path);
				MusicLibrary.markUnplayable(path);
				return;
			}

			// Battle resume uses a frame offset; the per-song "start at" uses
			// seconds, converted to frames now that we know the sample rate. A
			// resume frame always wins over start-at seconds. If the seek fails
			// (e.g. the file changed length), fall back to the start.
			long seekFrame = startFrame;
			if (seekFrame <= 0L && startSeconds > 0.0) {
				seekFrame = (long) (startSeconds * sampleRate);
			}
			// All playbackFrame writes on this thread are guarded by alive.get():
			// a stale thread that loses a stopThread() race can still be blocked
			// inside line.write() for up to ~0.2s, and an unguarded write after
			// that clobbered the frame a freshly started playback had just set,
			// corrupting the battle-resume position of the NEW track.
			if (seekFrame > 0L) {
				if (!vorbis.seek(seekFrame)) {
					BattleMusicClient.debug("[{}] seek to frame {} failed; starting from 0", name, seekFrame);
					vorbis.seekStart();
					if (alive.get()) playbackFrame = 0L;
				} else {
					if (alive.get()) playbackFrame = seekFrame;
					BattleMusicClient.debug("[{}] starting at frame {}", name, seekFrame);
				}
			}

			// Explicit small line buffer (2 decode chunks, ~0.19s at 44.1 kHz).
			// The implementation default is often ~0.5s or more, and since gain is
			// baked into the samples at write time, fades / the volume slider
			// lagged behind by the whole buffer (and the resume position ran ahead
			// of what was audible). Two chunks keeps fades snappy while leaving a
			// full chunk of slack against dropouts.
			line = vorbis.openLine(SAMPLES_PER_CHUNK * channels * 2 * 2);
			BattleMusicClient.debug("[{}] playing {} @ {} Hz, {} ch", name, path.getFileName(), sampleRate, channels);

			pcm = MemoryUtil.memAllocShort(SAMPLES_PER_CHUNK * channels);
			byte[] bytes = new byte[SAMPLES_PER_CHUNK * channels * 2];
			boolean justLooped = false;

			while (alive.get() && !Thread.currentThread().isInterrupted()) {
				pcm.clear();
				int n = vorbis.read(pcm);

				if (n <= 0) {
					if (loop && !justLooped) {
						BattleMusicClient.debug("[{}] looping {}", name, path.getFileName());
						vorbis.seekStart();
						if (alive.get()) playbackFrame = 0L;
						justLooped = true;
						continue;
					}
					if (loop) {
						// The track was just restarted and still produced nothing:
						// broken file. Bail out instead of spinning this thread at
						// 100% CPU on seek_start/read forever.
						BattleMusicClient.LOGGER.warn("[{}] {} produced no samples after a loop restart; stopping playback", name, path.getFileName());
						MusicLibrary.markUnplayable(path);
					} else {
						BattleMusicClient.debug("[{}] reached end of {}", name, path.getFileName());
					}
					break;
				}
				justLooped = false;

				if (alive.get()) playbackFrame = vorbis.sampleOffset();
				float gain = currentGain * outputVolume * trackGain;
				if (gain < 0f) gain = 0f;
				line.write(bytes, 0, vorbis.toBytes(pcm, n, gain, bytes));
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
			// Closes the decoder first, then frees the encoded buffer it reads from.
			if (vorbis != null) vorbis.close();
			// Only the still-current playback may clear shared status, so a stale
			// thread finishing never clobbers a freshly started one
			if (activeFlag == alive) {
				running = false;
			}
		}
	}

	// Decode-failure diagnostics, written to the game log (console + latest.log).
	// The #1 real-world cause is an "Ogg" file that is actually Ogg OPUS (YouTube
	// rippers and online converters commonly produce these), which STB Vorbis
	// cannot decode. Reported once per file per session; the blacklist in
	// MusicLibrary also keeps the picker off the file, so a broken track can't be
	// re-rolled and re-fail every tick.
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
		// The first Ogg page of an Opus stream carries the ASCII magic "OpusHead".
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
