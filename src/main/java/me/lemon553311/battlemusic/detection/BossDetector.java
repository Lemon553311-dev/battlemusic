package me.lemon553311.battlemusic.detection;

import me.lemon553311.battlemusic.BattleMusicClient;
import me.lemon553311.battlemusic.config.BattleMusicConfig;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * @author Dragon6555
 *
 * Detects special bosses near the player. Any boss present forces heavy battle
 * music and uses its own (larger) radius, since bosses are big fights that the
 * normal "5 mobs" rule would not capture.
 *
 * Built-ins: Ender Dragon, Wither, Warden. Add more via config.extraBossIds
 * (e.g. "minecraft:elder_guardian").
 *
 * Note: we intentionally do NOT import the registry-key class (its package
 * changed in 26.1). Comparing the registry key's string form keeps this robust
 * across mapping/refactor changes.
 */

public class BossDetector {
	private final BattleMusicConfig config;
	// Normalized entity ids, e.g. "minecraft:elder_guardian".
	private final Set<String> extraBossIds = new HashSet<>();

	// Throttle: bosses don't move ~48 blocks in 500 ms; scanning every tick wastes
	// the largest entity sweep the mod does. Cache the answer for a short window
	// and re-scan periodically. Reaction delay is at most CHECK_INTERVAL_TICKS
	// (10 = 0.5 s), which is shorter than the music fade-in anyway.
	private static final long CHECK_INTERVAL_TICKS = 10L;

	// Built-in "sub-boss" tier: tough single mobs the normal "5 mobs" rule misses.
	// Matched by registry id (no fragile imports), gated by config.includeMiniBosses.
	private static final Set<String> MINI_BOSS_IDS = Set.of(
			"minecraft:elder_guardian",
			"minecraft:ravager",
			"minecraft:evoker",
			"minecraft:piglin_brute");
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

	// {@code now} is a monotonic client-tick counter supplied by the state machine,
	// NOT world.getGameTime(); on time-locked servers a frozen getGameTime() pinned the
	// throttle so the boss scan never re-ran after its first call.
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
						BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()),
						String.format(Locale.ROOT, "%.1f", Math.sqrt(e.distanceToSqr(player))));
				lastResult = true;
				return true;
			}
		}
		lastResult = false;
		return false;
	}

	private boolean isBoss(Entity e) {
		if (e instanceof EnderDragon || e instanceof WitherBoss || e instanceof Warden) {
			return true;
		}
		if (!config.includeMiniBosses && extraBossIds.isEmpty()) {
			return false;
		}
		EntityType<?> type = e.getType();
		var key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
		if (key == null) return false;
		String id = key.toString();
		if (config.includeMiniBosses && MINI_BOSS_IDS.contains(id)) return true;
		return extraBossIds.contains(id);
	}
}