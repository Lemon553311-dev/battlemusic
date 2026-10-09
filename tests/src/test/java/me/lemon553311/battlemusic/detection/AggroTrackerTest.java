package me.lemon553311.battlemusic.detection;

import me.lemon553311.battlemusic.config.BattleMusicConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Aggro counting against a fake world. Ticks are driven manually like the
 * state machine's clientTick.
 */
class AggroTrackerTest {

	private BattleMusicConfig config;
	private AggroTracker tracker;
	private ClientLevel world;
	private LocalPlayer player;

	@BeforeEach
	void setUp() {
		config = new BattleMusicConfig();
		tracker = new AggroTracker(config);
		world = new ClientLevel();
		player = TestMobs.playerAt(0.0D, 0.0D, 0.0D);
		world.addEntity(player);
	}

	private TestMobs.HostileMob hostileAt(double x) {
		TestMobs.HostileMob mob = new TestMobs.HostileMob();
		mob.setPos(x, 0.0D, 0.0D);
		TestMobs.facePlayer(mob, player);
		world.addEntity(mob);
		return mob;
	}

	@Test
	void swingingHostileFacingUsCounted() {
		TestMobs.HostileMob mob = hostileAt(10.0D);
		mob.swinging = true;
		tracker.update(player, world, 1);
		assertEquals(1, tracker.getAggroCount());
		assertEquals(1, tracker.getLastInRangeCount());
		assertEquals(1, tracker.getLastAggroSignalCount());
	}

	@Test
	void calmHostileNotCounted() {
		hostileAt(10.0D); // facing us but standing still, not attacking
		tracker.update(player, world, 1);
		assertEquals(0, tracker.getAggroCount());
		assertEquals(1, tracker.getLastInRangeCount());
		assertEquals(0, tracker.getLastAggroSignalCount());
	}

	@Test
	void genuineApproachCountsAsEngagement() {
		TestMobs.HostileMob mob = hostileAt(10.0D);
		mob.deltaMovement = new Vec3(-1.0D, 0.0D, 0.0D);
		tracker.update(player, world, 1); // sets the approach anchor only
		assertEquals(0, tracker.getAggroCount());
		mob.setPos(9.0D, 0.0D, 0.0D);
		tracker.update(player, world, 2); // meaningfully closer -> engaged
		assertEquals(1, tracker.getAggroCount());
	}

	@Test
	void anyMovementCountsWhenClosingNotRequired() {
		config.engagementRequiresClosing = false;
		TestMobs.HostileMob mob = hostileAt(10.0D);
		mob.deltaMovement = new Vec3(0.0D, 0.0D, 1.0D); // strafing, not closing
		tracker.update(player, world, 1);
		assertEquals(1, tracker.getAggroCount());
	}

	@Test
	void legacyModeNeedsNonRecedingMob() {
		config.requireActiveEngagement = false;
		TestMobs.HostileMob mob = hostileAt(10.0D);
		mob.deltaMovement = new Vec3(-1.0D, 0.0D, 0.0D); // toward us
		tracker.update(player, world, 1);
		assertEquals(1, tracker.getAggroCount());
	}

	@Test
	void stickinessHoldsThenExpires() {
		TestMobs.HostileMob mob = hostileAt(10.0D);
		mob.swinging = true;
		tracker.update(player, world, 1);
		assertEquals(1, tracker.getAggroCount());
		mob.swinging = false; // goes quiet but keeps staring
		tracker.update(player, world, 2);
		assertEquals(1, tracker.getAggroCount());
		tracker.update(player, world, 30);
		assertEquals(1, tracker.getAggroCount());
		tracker.update(player, world, 100);
		assertEquals(0, tracker.getAggroCount());
	}

	@Test
	void calmNeutralIgnored() {
		TestMobs.NeutralMob wolf = new TestMobs.NeutralMob();
		wolf.setPos(3.0D, 0.0D, 0.0D);
		TestMobs.facePlayer(wolf, player);
		world.addEntity(wolf);
		tracker.update(player, world, 1);
		assertEquals(0, tracker.getAggroCount());
	}

	@Test
	void attackingNeutralCountedWhileFresh() {
		TestMobs.NeutralMob wolf = new TestMobs.NeutralMob();
		wolf.setPos(3.0D, 0.0D, 0.0D);
		TestMobs.facePlayer(wolf, player);
		wolf.swinging = true;
		world.addEntity(wolf);
		tracker.update(player, world, 1);
		assertEquals(1, tracker.getAggroCount());
		wolf.swinging = false;
		tracker.update(player, world, 200); // past the 5s neutral window
		assertEquals(0, tracker.getAggroCount());
	}

	@Test
	void neutralToggleOffIgnoresAttacks() {
		config.includeAttackingNeutrals = false;
		TestMobs.NeutralMob wolf = new TestMobs.NeutralMob();
		wolf.setPos(3.0D, 0.0D, 0.0D);
		TestMobs.facePlayer(wolf, player);
		wolf.swinging = true;
		world.addEntity(wolf);
		tracker.update(player, world, 1);
		assertEquals(0, tracker.getAggroCount());
	}

	@Test
	void perchedArcherShootingUsCounted() {
		TestMobs.HostileMob archer = hostileAt(10.0D); // still, not swinging
		Projectile arrow = new Projectile();
		arrow.setPos(5.0D, 0.0D, 0.0D);
		arrow.setOwner(archer);
		world.addEntity(arrow);
		tracker.update(player, world, 1);
		assertEquals(1, tracker.getAggroCount());
	}

	@Test
	void rangedToggleOffDropsPerchedArcher() {
		config.rangedAttacksCountAsEngagement = false;
		TestMobs.HostileMob archer = hostileAt(10.0D);
		Projectile arrow = new Projectile();
		arrow.setPos(5.0D, 0.0D, 0.0D);
		arrow.setOwner(archer);
		world.addEntity(arrow);
		tracker.update(player, world, 1);
		assertEquals(0, tracker.getAggroCount());
	}

	@Test
	void blockedLineOfSightNotCounted() {
		world.setClipType(net.minecraft.world.phys.HitResult.Type.BLOCK);
		TestMobs.HostileMob mob = hostileAt(10.0D);
		mob.swinging = true;
		tracker.update(player, world, 1);
		assertEquals(0, tracker.getAggroCount());
	}

	@Test
	void fusingCreeperCountsWithoutFacing() {
		Creeper creeper = new Creeper();
		creeper.setPos(10.0D, 0.0D, 0.0D);
		creeper.setYHeadRot(0.0F); // looking away, but fusing
		creeper.setSwellDir(1);
		world.addEntity(creeper);
		tracker.update(player, world, 1);
		assertEquals(1, tracker.getAggroCount());
	}

	@Test
	void despawnedMobPruned() {
		TestMobs.HostileMob mob = hostileAt(10.0D);
		mob.swinging = true;
		tracker.update(player, world, 1);
		assertEquals(1, tracker.getAggroCount());
		world.removeEntity(mob);
		tracker.update(player, world, 2);
		assertEquals(0, tracker.getAggroCount());
	}

	@Test
	void mobLeavingRadiusPruned() {
		TestMobs.HostileMob mob = hostileAt(10.0D);
		mob.swinging = true;
		tracker.update(player, world, 1);
		assertEquals(1, tracker.getAggroCount());
		mob.setPos(100.0D, 0.0D, 0.0D);
		tracker.update(player, world, 2);
		assertEquals(0, tracker.getAggroCount());
	}

	@Test
	void clearResetsCountAndSnapshots() {
		TestMobs.HostileMob mob = hostileAt(10.0D);
		mob.swinging = true;
		tracker.update(player, world, 1);
		tracker.clear();
		assertEquals(0, tracker.getAggroCount());
		assertEquals(0, tracker.getLastInRangeCount());
		assertEquals(0, tracker.getLastAggroSignalCount());
	}
}
