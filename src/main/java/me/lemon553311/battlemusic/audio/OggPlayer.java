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

import static org.lwjgl.stb.STBVorbis.*;
import static org.lwjgl.system.MemoryUtil.NULL;

public final class OggPlayer {
	private OggPlayer() {}
	private static final int SAMPLES_PER_CHUNK = 4096;

	public static boolean play(byte[] encoded, LineInit lineInit, Callbacks callbacks) {
		ByteBuffer encodedBuf = null;
		long decoder = NULL;
		SourceDataLine line = null;
		ShortBuffer pcm = null;

		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer error = stack.mallocInt(1);
			encodedBuf = MemoryUtil.memAlloc(encoded.length);
			encodedBuf.put(encoded);
			encodedBuf.flip();
			decoder = stb_vorbis_open_memory(encodedBuf, error, null);
			if (decoder == NULL) {
				BattleMusicClient.LOGGER.warn("[ogg] STB open failed (err {})", error.get(0));
				return false;
			}

			int channels;
			int sampleRate;
			STBVorbisInfo info = STBVorbisInfo.malloc();
			try {
				stb_vorbis_get_info(decoder, info);
				channels = info.channels();
				sampleRate = info.sample_rate();
				if (channels < 1 || channels > 2) {
					BattleMusicClient.LOGGER.warn("[ogg] unsupported channel count {}", channels);
					return false;
				}
			} finally {
				info.free();
			}

			if (callbacks != null) callbacks.onOpened(decoder, channels, sampleRate);

			AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);
			DataLine.Info dlInfo = new DataLine.Info(SourceDataLine.class, format);
			line = (SourceDataLine) AudioSystem.getLine(dlInfo);
			lineInit.init(format, line);
			line.start();

			pcm = MemoryUtil.memAllocShort(SAMPLES_PER_CHUNK * channels);
			byte[] bytes = new byte[SAMPLES_PER_CHUNK * channels * 2];

			while (true) {
				pcm.clear();
				int n = stb_vorbis_get_samples_short_interleaved(decoder, channels, pcm);
				if (n <= 0) {
					if (callbacks != null && !callbacks.onEndOfStream(decoder)) break;
					continue;
				}
				if (callbacks != null && !callbacks.onSamples(pcm, n, channels, bytes, line, decoder)) break;
			}
			line.drain();
			return true;
		} catch (Throwable t) {
			BattleMusicClient.LOGGER.warn("[ogg] playback error", t);
			return false;
		} finally {
			if (pcm != null) { try { MemoryUtil.memFree(pcm); } catch (Throwable ignored) {} }
			if (line != null) { try { line.flush(); line.stop(); line.close(); } catch (Throwable ignored) {} }
			if (decoder != NULL) { try { stb_vorbis_close(decoder); } catch (Throwable ignored) {} }
			if (encodedBuf != null) { try { MemoryUtil.memFree(encodedBuf); } catch (Throwable ignored) {} }
		}
	}

	@FunctionalInterface
	public interface LineInit {
		void init(AudioFormat format, SourceDataLine line) throws Exception;
	}

	public interface Callbacks {
		default void onOpened(long decoder, int channels, int sampleRate) {}
		boolean onSamples(ShortBuffer pcm, int frameCount, int channels, byte[] byteBuf,
				SourceDataLine line, long decoder);
		default boolean onEndOfStream(long decoder) { return false; }
	}
}
