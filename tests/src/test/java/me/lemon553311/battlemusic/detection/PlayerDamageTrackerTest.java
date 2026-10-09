package me.lemon553311.battlemusic.detection;

import me.lemon553311.battlemusic.config.BattleMusicConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PvP damage attribution against a fake world. Ticks are driven manually
 * (1, 2, ...) like the state machine's clientTick.
 */
class PlayerDamageTrackerTest {

	private BattleMusicConfig config;
	private PlayerDamageTracker tracker;
	private ClientLevel world;
	private LocalPlayer self;
	private Player foe;

	@BeforeEach
	void setUp() {
		config = new BattleMusicConfig();
		tracker = new PlayerDamageTracker(config);
		world = new ClientLevel();
		self = TestMobs.playerAt(0.0D, 0.0D, 0.0D);
		self.setHealth(20.0F);
		world.addEntity(self);
		foe = new Player();
		foe.setPos(3.0D, 0.0D, 0.0D);
		TestMobs.facePlayer(foe, self);
		world.addEntity(foe);
	}

	/** Arm a melee threat on the given tick (foe mid-swing, facing us). */
	private void swingAt(int tick) {
		foe.swinging = true;
		tracker.update(self, world, tick);
		foe.swinging = false;
	}

	@Test
	void meleeHitAttributedAndTriggers() {
		tracker.update(self, world, 1); // baselines only
		foe.swinging = true;
		self.setHealth(14.0F);
		self.hurtTime = 10;
		tracker.update(self, world, 2);
		assertTrue(tracker.receivedThisTick());
		assertTrue(tracker.isTriggered());
		assertEquals(6.0D, tracker.getRecentDamageHp());
	}

	@Test
	void maskedHitCountsNominalOneHp() {
		tracker.update(self, world, 1);
		foe.swinging = true;
		self.hurtTime = 10; // edge, but absorption hid the health change
		tracker.update(self, world, 2);
		assertTrue(tracker.receivedThisTick());
		assertEquals(1.0D, tracker.getRecentDamageHp());
		assertFalse(tracker.isTriggered());
	}

	@Test
	void singleHitCappedAtTwentyHp() {
		self.setHealth(40.0F);
		tracker.update(self, world, 1);
		foe.swinging = true;
		self.setHealth(10.0F);
		self.hurtTime = 10;
		tracker.update(self, world, 2);
		assertEquals(20.0D, tracker.getRecentDamageHp());
	}

	@Test
	void windowExpiryUntriggers() {
		tracker.update(self, world, 1);
		foe.swinging = true;
		self.setHealth(14.0F);
		self.hurtTime = 10;
		tracker.update(self, world, 2);
		assertTrue(tracker.isTriggered());
		tracker.update(self, world, 103); // past the 5s (100-tick) window
		assertFalse(tracker.isTriggered());
		assertEquals(0.0D, tracker.getRecentDamageHp());
	}

	@Test
	void fallingWithStaleThreatNotAttributed() {
		tracker.update(self, world, 1); // baselines only
		swingAt(2); // threat, but no damage lands
		foe.swinging = false;
		self.fallDistance = 3.0F;
		self.setHealth(15.0F);
		self.hurtTime = 10;
		tracker.update(self, world, 3);
		assertFalse(tracker.receivedThisTick());
		assertEquals(0.0D, tracker.getRecentDamageHp());
	}

	@Test
	void knockbackOverridesEnvironmentalVeto() {
		tracker.update(self, world, 1); // baselines only
		swingAt(2);
		foe.swinging = false;
		self.fallDistance = 3.0F;
		self.deltaMovement = new Vec3(0.5D, 0.0D, 0.0D);
		self.setHealth(15.0F);
		self.hurtTime = 10;
		tracker.update(self, world, 3);
		assertTrue(tracker.receivedThisTick());
		assertEquals(5.0D, tracker.getRecentDamageHp());
	}

	@Test
	void sameTickSwingOverridesEnvironmentalVeto() {
		tracker.update(self, world, 1);
		self.fallDistance = 3.0F;
		foe.swinging = true; // swinging exactly as the hit lands
		self.setHealth(15.0F);
		self.hurtTime = 10;
		tracker.update(self, world, 2);
		assertTrue(tracker.receivedThisTick());
	}

	@Test
	void nearbyPlayerProjectileAttributed() {
		tracker.update(self, world, 1);
		world.removeEntity(foe);
		Player archer = new Player();
		archer.setPos(30.0D, 0.0D, 0.0D);
		world.addEntity(archer);
		Projectile arrow = new Projectile();
		arrow.setPos(1.0D, 0.0D, 0.0D);
		arrow.setOwner(archer);
		world.addEntity(arrow);
		self.setHealth(17.0F);
		self.hurtTime = 10;
		tracker.update(self, world, 2);
		assertTrue(tracker.receivedThisTick());
		assertEquals(3.0D, tracker.getRecentDamageHp());
	}

	@Test
	void disabledTriggerClearsState() {
		tracker.update(self, world, 1);
		foe.swinging = true;
		self.setHealth(14.0F);
		self.hurtTime = 10;
		tracker.update(self, world, 2);
		assertTrue(tracker.isTriggered());
		config.playerDamageTriggerEnabled = false;
		tracker.update(self, world, 3);
		assertFalse(tracker.isTriggered());
		assertEquals(0.0D, tracker.getRecentDamageHp());
		config.playerDamageTriggerEnabled = true;
		tracker.update(self, world, 4); // re-baselines, no spurious hit
		assertFalse(tracker.receivedThisTick());
	}
}
