package me.lemon553311.battlemusic.audio;

import me.lemon553311.battlemusic.BattleMusicClient;

import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

import static org.lwjgl.stb.STBVorbis.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Shared STB Vorbis decode plumbing for {@link MusicChannel} and the one-shot
 * alert player: opening a decoder over an in-memory copy of an ogg file,
 * reading stream info, seeking, chunked PCM reads, gain scaling, and Java
 * Sound line setup.
 *
 * Decoding always happens from memory (never stb_vorbis_open_filename): LWJGL
 * hands stb the path as UTF-8 bytes, but stb opens it with C fopen(), which on
 * Windows expects the legacy ANSI codepage. Any non-ASCII path component (a
 * track name or a Windows user name) failed to open and playback stayed
 * silent. Decoding from memory has no file path to break.
 *
 * The encoded buffer must outlive the decoder - stb reads from it for the
 * decoder's whole lifetime - which is exactly the invariant this class exists
 * to encapsulate: {@link #close()} closes the decoder first, then frees the
 * buffer.
 */
public final class VorbisStream implements AutoCloseable {
	private final long decoder;
	private final ByteBuffer encoded;
	private final int channels;
	private final int sampleRate;
	private boolean closed = false;

	private VorbisStream(long decoder, ByteBuffer encoded, int channels, int sampleRate) {
		this.decoder = decoder;
		this.encoded = encoded;
		this.channels = channels;
		this.sampleRate = sampleRate;
	}

	/**
	 * Open a decoder over an in-memory ogg. Returns null (and logs a warning
	 * prefixed with {@code logTag}) when STB cannot open the data. The channel
	 * count is NOT validated here; callers decide how to report unsupported
	 * layouts (the music channels also blacklist the file, one-shots only warn).
	 */
	public static VorbisStream open(byte[] data, String logTag) {
		ByteBuffer encoded = MemoryUtil.memAlloc(data.length);
		encoded.put(data);
		encoded.flip();
		long decoder;
		int err = 0;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer error = stack.mallocInt(1);
			decoder = stb_vorbis_open_memory(encoded, error, null);
			if (decoder == NULL) err = error.get(0);
		}
		if (decoder == NULL) {
			MemoryUtil.memFree(encoded);
			BattleMusicClient.LOGGER.warn("{} STB Vorbis open failed (err {})", logTag, err);
			return null;
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
		return new VorbisStream(decoder, encoded, channels, sampleRate);
	}

	public int channels() {
		return channels;
	}

	public int sampleRate() {
		return sampleRate;
	}

	/** Seek to an absolute sample-frame offset. Returns false when stb refuses. */
	public boolean seek(long frame) {
		return stb_vorbis_seek(decoder, (int) frame);
	}

	public void seekStart() {
		stb_vorbis_seek_start(decoder);
	}

	/** Sample-frame offset of the next sample to decode (for battle resume). */
	public long sampleOffset() {
		return stb_vorbis_get_sample_offset(decoder);
	}

	/** Decode the next chunk of interleaved 16-bit samples. Returns frames read per channel. */
	public int read(ShortBuffer pcm) {
		return stb_vorbis_get_samples_short_interleaved(decoder, channels, pcm);
	}

	/**
	 * Open and start a Java Sound line matching this stream (signed 16-bit
	 * little-endian). {@code bufferBytes <= 0} uses the implementation default,
	 * fine for one-shots; the music channels pass a small explicit buffer so
	 * fades stay snappy (gain is baked into the samples at write time).
	 */
	public SourceDataLine openLine(int bufferBytes) throws LineUnavailableException {
		AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);
		DataLine.Info dlInfo = new DataLine.Info(SourceDataLine.class, format);
		SourceDataLine line = (SourceDataLine) AudioSystem.getLine(dlInfo);
		if (bufferBytes > 0) {
			line.open(format, bufferBytes);
		} else {
			line.open(format);
		}
		line.start();
		return line;
	}

	/**
	 * Scale {@code frames} interleaved frames from {@code pcm} by {@code gain},
	 * clamp to the 16-bit range, and write them little-endian into {@code out}.
	 * Returns the number of bytes written (for the line.write call).
	 */
	public int toBytes(ShortBuffer pcm, int frames, float gain, byte[] out) {
		int sampleCount = frames * channels;
		for (int i = 0; i < sampleCount; i++) {
			int v = (int) (pcm.get(i) * gain);
			if (v > 32767) v = 32767;
			else if (v < -32768) v = -32768;
			out[i * 2] = (byte) (v & 0xFF);
			out[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
		}
		return sampleCount * 2;
	}

	/** Idempotent: closes the decoder, then frees the encoded buffer it reads from. */
	@Override
	public void close() {
		if (closed) return;
		closed = true;
		try { stb_vorbis_close(decoder); } catch (Throwable ignored) {}
		try { MemoryUtil.memFree(encoded); } catch (Throwable ignored) {}
	}
}
