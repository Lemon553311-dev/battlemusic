package me.lemon553311.battlemusic.detection;

import me.lemon553311.battlemusic.BattleMusicClient;
import me.lemon553311.battlemusic.config.BattleMusicConfig;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
//? if >=1.19.3 {
import net.minecraft.core.registries.BuiltInRegistries;
//?} else {
/*import net.minecraft.core.Registry;
*///?}
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
//? if >=1.19 {
import net.minecraft.world.entity.monster.warden.Warden;
//?}
import net.minecraft.world.phys.AABB;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Detects bosses near the player; any boss forces heavy battle music with its
 * own (larger) radius. Built-ins: Ender Dragon, Wither, Warden, plus
 * config.extraBossIds. Registry keys are compared as strings - we intentionally
 * never import the registry-key class since its name changed in 26.1.
 */

public class BossDetector {
	private final BattleMusicConfig config;
	// Normalized entity ids, e.g. "minecraft:elder_guardian".
	private final Set<String> extraBossIds = new HashSet<>();

	// bosses don't move ~48 blocks in 500ms; cache the answer for a short window.
	// reaction delay is at most 0.5s, shorter than the music fade-in anyway.
	private static final long CHECK_INTERVAL_TICKS = 10L;

	// tough single mobs the normal "5 mobs" rule misses, matched by registry id
	private static final Set<String> MINI_BOSS_IDS = new HashSet<>(Arrays.asList(
			"minecraft:elder_guardian",
			"minecraft:ravager",
			"minecraft:evoker",
			"minecraft:piglin_brute"));
	private long lastCheckTick = Long.MIN_VALUE;
	private boolean lastResult = false;

	public BossDetector(BattleMusicConfig config) {
		this.config = config;
		refreshExtraIds();
	}

	public void refreshExtraIds() {
		extraBossIds.clear();
		for (String id : config.extraBossIds) {
			if (id == null) continue;
			String norm = id.trim().toLowerCase(Locale.ROOT);
			if (norm.isEmpty()) continue;
			if (!norm.contains(":")) norm = "minecraft:" + norm;
			extraBossIds.add(norm);
		}
	}

	public void clear() {
		lastCheckTick = Long.MIN_VALUE;
		lastResult = false;
	}

	// now is a monotonic client-tick counter, NOT world.getGameTime() - a frozen
	// getGameTime() on time-locked servers pinned the throttle forever
	public boolean anyBossNearby(LocalPlayer player, ClientLevel world, long now) {
		if (player == null || world == null) return false;
		if (lastCheckTick != Long.MIN_VALUE && now >= lastCheckTick && now - lastCheckTick < CHECK_INTERVAL_TICKS) {
			return lastResult;
		}
		lastCheckTick = now;
		double r = config.bossRadius;
		AABB area = player.getBoundingBox().inflate(r);
		double rSq = r * r;
		for (Entity e : world.getEntities(player, area)) {
			if (e.distanceToSqr(player) > rSq) continue;
			if (isBoss(e)) {
				BattleMusicClient.debug("Boss detected: {} (~{} blocks)",
						//? if >=1.19.3 {
						BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()),
						//?} else {
						/*Registry.ENTITY_TYPE.getKey(e.getType()),
						*///?}
						String.format(Locale.ROOT, "%.1f", Math.sqrt(e.distanceToSqr(player))));
				lastResult = true;
				return true;
			}
		}
		lastResult = false;
		return false;
	}

	private boolean isBoss(Entity e) {
		if (e instanceof EnderDragon || e instanceof WitherBoss) {
			return true;
		}
		//? if >=1.19 {
		if (e instanceof Warden) {
			return true;
		}
		//?}
		if (!config.includeMiniBosses && extraBossIds.isEmpty()) {
			return false;
		}
		EntityType<?> type = e.getType();
		// never name the registry-key type (renamed ResourceLocation -> Identifier in
		// 26.1); toString() inline needs no import at all
		//? if >=1.19.3 {
		String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
		//?} else {
		/*String id = Registry.ENTITY_TYPE.getKey(type).toString();
		*///?}
		if (config.includeMiniBosses && MINI_BOSS_IDS.contains(id)) return true;
		return extraBossIds.contains(id);
	}
}