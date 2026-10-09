package me.lemon553311.battlemusic.lasttotem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Damage-gated 2 -> 1 edge detection, tick by tick. No Minecraft types.
 */
class TotemEdgeDetectorTest {

	private TotemEdgeDetector edge;

	@BeforeEach
	void setUp() {
		edge = new TotemEdgeDetector();
	}

	/** First sample only sets the baseline, never fires. */
	@Test
	void baselineNeverFires() {
		assertFalse(edge.update(0, 2));
		assertFalse(edge.update(0, 2));
		assertFalse(edge.update(0, 1));
	}

	/** A real pop (damage + 2 -> 1) fires exactly once. */
	@Test
	void realPopFiresOnce() {
		assertFalse(edge.update(0, 2));
		assertFalse(edge.update(10, 2)); // damage lands, count unchanged
		assertTrue(edge.update(10, 1)); // the pop
		assertFalse(edge.update(10, 1));
		assertFalse(edge.update(0, 1));
	}

	/** A 3 -> 1 collapse is an inventory move, never a pop. */
	@Test
	void bigDropNeverFires() {
		assertFalse(edge.update(0, 3));
		assertFalse(edge.update(0, 1));
		for (int i = 0; i < 30; i++) {
			assertFalse(edge.update(0, 1));
		}
	}

	/** Moving one totem into a chest (2 -> 1, no damage) stays silent. */
	@Test
	void chestMoveStaysSilent() {
		assertFalse(edge.update(0, 2));
		assertFalse(edge.update(0, 1));
		for (int i = 0; i < 30; i++) {
			assertFalse(edge.update(0, 1));
		}
	}

	/** Damage packet landing a few ticks after the drop still fires. */
	@Test
	void lateDamagePacketStillFires() {
		assertFalse(edge.update(0, 2));
		assertFalse(edge.update(0, 1)); // drop first, no damage yet
		for (int i = 0; i < 4; i++) {
			assertFalse(edge.update(0, 1));
		}
		assertTrue(edge.update(10, 1)); // damage arrives late
		assertFalse(edge.update(10, 1));
	}

	/** Damage long before the drop does not authorize it. */
	@Test
	void staleDamageDoesNotAuthorizeDrop() {
		assertFalse(edge.update(0, 2));
		assertFalse(edge.update(10, 2)); // a fight, long ago
		for (int i = 0; i < 30; i++) {
			assertFalse(edge.update(0, 2));
		}
		assertFalse(edge.update(0, 1)); // chest move much later
		for (int i = 0; i < 15; i++) {
			assertFalse(edge.update(0, 1));
		}
	}

	/** Picking a totem back up clears a pending drop. */
	@Test
	void pickupClearsPendingDrop() {
		assertFalse(edge.update(0, 2));
		assertFalse(edge.update(0, 1)); // pending...
		assertFalse(edge.update(0, 2)); // ...picked back up, pending cleared
		assertFalse(edge.update(0, 1)); // moved again, still no damage
		for (int i = 0; i < 15; i++) {
			assertFalse(edge.update(0, 1));
		}
	}

	/** Reset rearms the baseline. */
	@Test
	void resetRearmsBaseline() {
		assertFalse(edge.update(0, 2));
		assertFalse(edge.update(0, 1));
		edge.reset();
		assertFalse(edge.update(0, 1)); // new baseline, never fires
		assertFalse(edge.update(10, 2));
		assertTrue(edge.update(10, 1));
	}

	/** Hurt-time decay (10 -> 9 -> 8 ...) is not a new hit. */
	@Test
	void hurtTimeDecayDoesNotRefire() {
		assertFalse(edge.update(0, 2));
		assertFalse(edge.update(10, 2));
		assertFalse(edge.update(9, 2));
		assertFalse(edge.update(8, 2));
		assertTrue(edge.update(8, 1)); // still within the damage window
	}
}
