package me.lemon553311.battlemusic.audio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fade-curve math on a real MusicChannel. Playback is never started, so no
 * audio hardware, files or native decoding are involved.
 */
class FadeMathTest {

	private MusicChannel channel;

	@BeforeEach
	void setUp() {
		channel = new MusicChannel(new AudioEngine(), "test");
	}

	@Test
	void fadeUp_reachesTargetOnSchedule() {
		channel.fadeTo(1.0F, 2.0D, false);
		for (int i = 0; i < 40; i++) {
			channel.update(0.05D);
		}
		assertEquals(1.0F, channel.getCurrentGain(), 1e-4F);
		assertTrue(channel.isAudible());
	}

	@Test
	void fadeUp_isHalfwayAtHalfTime() {
		channel.fadeTo(1.0F, 2.0D, false);
		for (int i = 0; i < 20; i++) {
			channel.update(0.05D);
		}
		assertEquals(0.5F, channel.getCurrentGain(), 1e-4F);
	}

	@Test
	void fadeDownWithStop_cutsAndClears() {
		channel.fadeTo(1.0F, 0.0D, false);
		channel.update(0.05D);
		assertTrue(channel.isAudible());
		channel.fadeTo(0.0F, 1.0D, true);
		for (int i = 0; i < 20; i++) {
			channel.update(0.05D);
		}
		assertEquals(0.0F, channel.getCurrentGain(), 1e-4F);
		assertFalse(channel.isAudible());
		assertNull(channel.getLoaded());
	}

	@Test
	void zeroDuration_snapsInstantly() {
		channel.fadeTo(1.0F, 0.0D, false);
		channel.update(0.05D);
		assertEquals(1.0F, channel.getCurrentGain(), 1e-4F);
	}

	@Test
	void targetGain_visibleBeforeUpdate() {
		channel.fadeTo(1.0F, 5.0D, false);
		assertEquals(1.0F, channel.getTargetGain(), 1e-6F);
		assertEquals(0.0F, channel.getCurrentGain(), 1e-6F);
	}

	@Test
	void hardStop_clearsImmediately() {
		channel.fadeTo(1.0F, 5.0D, false);
		channel.update(0.5D);
		assertTrue(channel.getCurrentGain() > 0.0F);
		channel.hardStop();
		assertEquals(0.0F, channel.getCurrentGain(), 1e-6F);
		assertEquals(0.0F, channel.getTargetGain(), 1e-6F);
		assertFalse(channel.isAudible());
	}
}
