package me.lemon553311.battlemusic.detection;

import me.lemon553311.battlemusic.config.BattleMusicConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boss detection and extra-ID normalization against a fake world/registry.
 */
class BossDetectorTest {

	private BattleMusicConfig config;
	private ClientLevel world;
	private LocalPlayer player;

	@BeforeEach
	void setUp() {
		config = new BattleMusicConfig();
		world = new ClientLevel();
		player = TestMobs.playerAt(0.0D, 0.0D, 0.0D);
		BuiltInRegistries.ENTITY_TYPE.clear();
	}

	private EntityType<?> type(String id) {
		EntityType<?> t = new EntityType<>();
		BuiltInRegistries.ENTITY_TYPE.register(t, id);
		return t;
	}

	@Test
	void extraIdsNormalized() {
		config.extraBossIds = new ArrayList<>(Arrays.asList(
				" Elder_Guardian ", "MOD:BOSS", "", "minecraft:wither", null));
		BossDetector detector = new BossDetector(config);
		TestMobs.HostileMob mob = new TestMobs.HostileMob();
		mob.setPos(5.0D, 0.0D, 0.0D);
		mob.setType(type("minecraft:elder_guardian"));
		world.addEntity(mob);
		assertTrue(detector.anyBossNearby(player, world, 11));
	}

	@Test
	void extraIdWithExplicitNamespaceMatches() {
		config.extraBossIds = new ArrayList<>(Arrays.asList("mod:boss"));
		BossDetector detector = new BossDetector(config);
		TestMobs.HostileMob mob = new TestMobs.HostileMob();
		mob.setPos(5.0D, 0.0D, 0.0D);
		mob.setType(type("mod:boss"));
		world.addEntity(mob);
		assertTrue(detector.anyBossNearby(player, world, 11));
	}

	@Test
	void unlistedMobIsNotABoss() {
		config.includeMiniBosses = false;
		BossDetector detector = new BossDetector(config);
		TestMobs.HostileMob mob = new TestMobs.HostileMob();
		mob.setPos(5.0D, 0.0D, 0.0D);
		mob.setType(type("minecraft:zombie"));
		world.addEntity(mob);
		assertFalse(detector.anyBossNearby(player, world, 11));
	}

	@Test
	void dragonNearbyIsAlwaysABoss() {
		BossDetector detector = new BossDetector(config);
		EnderDragon dragon = new EnderDragon();
		dragon.setPos(10.0D, 0.0D, 0.0D);
		world.addEntity(dragon);
		assertTrue(detector.anyBossNearby(player, world, 11));
	}

	@Test
	void witherBeyondRadiusIsNotDetected() {
		BossDetector detector = new BossDetector(config);
		WitherBoss wither = new WitherBoss();
		wither.setPos(100.0D, 0.0D, 0.0D);
		world.addEntity(wither);
		assertFalse(detector.anyBossNearby(player, world, 11));
	}

	@Test
	void miniBossToggleRespected() {
		TestMobs.HostileMob evoker = new TestMobs.HostileMob();
		evoker.setPos(5.0D, 0.0D, 0.0D);
		evoker.setType(type("minecraft:evoker"));
		world.addEntity(evoker);
		assertTrue(new BossDetector(config).anyBossNearby(player, world, 11));
		config.includeMiniBosses = false;
		assertFalse(new BossDetector(config).anyBossNearby(player, world, 11));
	}

	@Test
	void sameTickResultIsStable() {
		BossDetector detector = new BossDetector(config);
		EnderDragon dragon = new EnderDragon();
		dragon.setPos(10.0D, 0.0D, 0.0D);
		world.addEntity(dragon);
		assertTrue(detector.anyBossNearby(player, world, 11));
		assertTrue(detector.anyBossNearby(player, world, 11));
	}
}
