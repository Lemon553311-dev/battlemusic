package me.lemon553311.battlemusic.detection;

import me.lemon553311.battlemusic.config.BattleMusicConfig;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 *
 * Approximates how many hostile mobs are aggroed on the player.
 *
 * i dont fucking know
 */

public class AggroTracker {
	private final BattleMusicConfig config;

	// entityId - ticks when it last looked aggroed
	private final Map<Integer, Long> lastAggroTick = new HashMap<>();
	// entityId - squared distance to player on the previous evaluation
	private final Map<Integer, Double> lastDistSq = new HashMap<>();
	// entityId - world position on the previous evaluation, to measure REAL movement
	private final Map<Integer, Vec3> lastPos = new HashMap<>();
	// entityId - last tick the mob actually moved toward us, swung, or shot at us
	private final Map<Integer, Long> lastActiveTick = new HashMap<>();
	// entityId - closest squared distance the mob has held within the current window.
	// Movement only reads as "engaged" when the mob beats this anchor, so a mob pacing
	// or jittering at a roughly steady distance stops sustaining the music.
	private final Map<Integer, Double> approachAnchorSq = new HashMap<>();
	private final Map<Integer, Long> approachAnchorTick = new HashMap<>();
	// entityId - last tick a normally-neutral mob (wolf, iron golem, ...) actually
	// attacked us. Lets angry neutrals count without ever firing on calm animals.
	private final Map<Integer, Long> neutralCombatTick = new HashMap<>();

	// A mob must move at least this much (blocks/tick, squared) to read as "moving".
	// Filters interpolation jitter so a truly stationary mob reads as idle.
	private static final double MOVE_EPSILON_SQ = 0.0025; // ~0.05 blocks/tick
	// How long after its last real movement/attack a mob still counts as engaged, so
	// brief pauses between steps or attack swings don't make it flicker out.
	private static final long ACTIVE_WINDOW_TICKS = 60L; // 3 seconds
	// A mob must get at least this much closer (blocks) than its window anchor for the
	// movement to read as a genuine approach rather than noise or pacing in place.
	private static final double APPROACH_MARGIN = 0.35;
	// Reach within which a swinging neutral mob is treated as actually attacking us.
	private static final double NEUTRAL_REACH_SQ = 4.5 * 4.5;
	// How long a neutral mob keeps counting after its last attack on us (so it does not
	// flicker out between swings).
	private static final long NEUTRAL_COMBAT_WINDOW_TICKS = 100L; // 5 seconds
	// A hostile projectile this close to us means we are being shot at.
	private static final double PROJECTILE_ENGAGE_NEAR_SQ = 8.0 * 8.0;
	// Vertical aim tolerance (degrees). Looser than the yaw tolerance so ordinary height
	// differences in a real fight still count, while a mob that can only crane
	// near-straight up or down at you (you climbed out of its reach) does not.
	private static final double VERTICAL_AIM_TOLERANCE_DEG = 70.0;

	private int aggroCount = 0;
	// Debug snapshot from the last evaluation.
	private int lastInRange = 0;
	private int lastAggroSignals = 0;

	public AggroTracker(BattleMusicConfig config) {
		this.config = config;
	}

	public int getAggroCount() {
		return aggroCount;
	}

	// Hostile mobs found inside the radius on the last evaluation
	public int getLastInRangeCount() {
		return lastInRange;
	}

	// Mobs that produced a fresh aggro signal on the last evaluation
	public int getLastAggroSignalCount() {
		return lastAggroSignals;
	}

	public void clear() {
		lastAggroTick.clear();
		lastDistSq.clear();
		lastPos.clear();
		lastActiveTick.clear();
		approachAnchorSq.clear();
		approachAnchorTick.clear();
		neutralCombatTick.clear();
		aggroCount = 0;
	}

	// {@code now} is a monotonic client-tick counter supplied by the state machine,
	// NOT world.getGameTime() (which freezes on time-locked servers like Hypixel and
	// would stop aggro stickiness from ever expiring there).
	public void update(LocalPlayer player, ClientLevel world, long now) {
		if (player == null || world == null) {
			clear();
			return;
		}

		final double radius = config.detectionRadius;
		final double radiusSq = radius * radius;
		final long stickinessTicks = Math.round(config.aggroStickinessSeconds * 20.0);
		final Vec3 playerEye = player.getEyePosition();

		// Track which mobs are in range this tick so we can prune stale memory
		// afterwards (otherwise the per-entity maps leak ids of despawned mobs).
		final Set<Integer> inRangeNow = new HashSet<>();
		int aggroSignals = 0;

		// One entity sweep: collect in-range mobs and, at the same time, note which mobs
		// are shooting at us (a projectile of theirs is right next to the player).
		final AABB area = player.getBoundingBox().inflate(radius);
		final List<Mob> mobs = new ArrayList<>();
		final Set<Integer> rangedAttackerIds = new HashSet<>();
		for (Entity e : world.getEntities(player, area)) {
			if (e instanceof Mob mob) {
				if (mob.distanceToSqr(player) <= radiusSq) mobs.add(mob);
			} else if (config.rangedAttacksCountAsEngagement && e instanceof Projectile proj) {
				if (proj.distanceToSqr(player) <= PROJECTILE_ENGAGE_NEAR_SQ) {
					Entity owner = proj.getOwner();
					if (owner instanceof Mob ownerMob) rangedAttackerIds.add(ownerMob.getId());
				}
			}
		}

		for (Mob mob : mobs) {
			final int id = mob.getId();
			final double distSq = mob.distanceToSqr(player);
			final boolean enemy = isHostile(mob);
			final boolean shootingAtUs = rangedAttackerIds.contains(id);

			// Normally-neutral mobs only count while they are actually attacking us. We read
			// that from synced combat actions (a melee swing aimed at us, or one of their
			// projectiles next to us), never from server-only AI targets, so a calm animal
			// wandering past never starts a battle.
			if (!enemy) {
				if (!config.includeAttackingNeutrals) continue;
				boolean meleeAtUs = mob.swinging && distSq <= NEUTRAL_REACH_SQ
						&& isHeadAimedAtPlayer(mob, player, playerEye);
				if (meleeAtUs || shootingAtUs) neutralCombatTick.put(id, now);
				Long nt = neutralCombatTick.get(id);
				if (nt == null || now - nt > NEUTRAL_COMBAT_WINDOW_TICKS) continue;
			}

			inRangeNow.add(id);

			boolean closing = isClosing(mob, player, distSq);
			updateActivity(mob, id, now, distSq, shootingAtUs);
			lastDistSq.put(id, distSq);

			if (looksAggroed(mob, player, world, playerEye, closing, now)) {
				lastAggroTick.put(id, now);
				aggroSignals++;
			}
		}

		// Drop memory for anything that left the radius or despawned.
		lastDistSq.keySet().retainAll(inRangeNow);
		lastPos.keySet().retainAll(inRangeNow);
		lastActiveTick.keySet().retainAll(inRangeNow);
		approachAnchorSq.keySet().retainAll(inRangeNow);
		approachAnchorTick.keySet().retainAll(inRangeNow);
		neutralCombatTick.keySet().retainAll(inRangeNow);

		// Count entities still inside the stickiness window, and prune the rest.
		int count = 0;
		var it = lastAggroTick.entrySet().iterator();
		while (it.hasNext()) {
			var entry = it.next();
			Entity tracked = world.getEntity(entry.getKey());
			boolean expired = now - entry.getValue() > stickinessTicks;
			boolean gone = tracked == null || !tracked.isAlive()
					|| tracked.distanceToSqr(player) > radiusSq;
			if (expired || gone) {
				it.remove();
			} else {
				count++;
			}
		}
		aggroCount = count;
		lastInRange = inRangeNow.size();
		lastAggroSignals = aggroSignals;
	}

	private boolean isClosing(Mob mob, LocalPlayer player, double distSq) {
		Double prev = lastDistSq.get(mob.getId());
		if (prev != null) {
			return distSq <= prev + 1.0e-3; // not getting farther away
		}
		Vec3 v = mob.getDeltaMovement();
		if (v.lengthSqr() < 1.0e-4) return true; // standing still: eligible
		double dx = player.getX() - mob.getX();
		double dz = player.getZ() - mob.getZ();
		return v.x * dx + v.z * dz >= 0.0; // velocity points toward the player
	}

	private boolean looksAggroed(Mob mob, LocalPlayer player, ClientLevel world,
	                             Vec3 playerEye, boolean closing, long now) {
		// Strong explicit signals shortcut the heuristic. A fusing creeper, an angry
		// enderman or a warden is a real threat even while standing still, so these
		// intentionally bypass the "must be actively engaged" test below.
		if (HostileStateSignals.isObviouslyAggressive(mob)) {
			return !config.requireLineOfSight || hasLineOfSight(mob, world, playerEye);
		}

		if (!isHeadAimedAtPlayer(mob, player, playerEye)) return false;
		if (config.requireActiveEngagement) {
			// Sustain the battle only while the mob is actually doing something:
			// approaching, circling-while-attacking, or shooting. A mob that just stands
			// around (stuck, out of reach, only turning its head) is NOT engaged, so a lone
			// lingering mob can no longer keep the music going with no action.
			if (!isActivelyEngaged(mob, now)) return false;
		} else {
			// Legacy behavior: any non-receding mob (including a stationary one) counts.
			if (!closing) return false;
		}
		if (config.requireLineOfSight && !hasLineOfSight(mob, world, playerEye)) return false;
		return true;
	}

	// Record whether the mob actually engaged this tick: a REAL approach (net closing,
	// not head-turning or pacing in place), a melee swing, or shooting at us. Head-turning
	// alone never refreshes this, which is what stops an idle mob from sustaining music.
	private void updateActivity(Mob mob, int id, long now, double distSq, boolean shootingAtUs) {
		Vec3 pos = mob.position();
		Vec3 prev = lastPos.put(id, pos);
		double moveSq;
		if (prev == null) {
			Vec3 v = mob.getDeltaMovement();
			moveSq = v.x * v.x + v.z * v.z;
		} else {
			double dx = pos.x - prev.x;
			double dz = pos.z - prev.z;
			moveSq = dx * dx + dz * dz;
		}

		boolean moveCounts;
		if (config.engagementRequiresClosing) {
			// Only a genuine net approach counts: the mob must beat the closest distance it
			// has held this window. Pacing or circling at a steady distance does not.
			moveCounts = moveSq > MOVE_EPSILON_SQ && isNetApproaching(id, now, distSq);
		} else {
			// Legacy: any real positional movement counts.
			moveCounts = moveSq > MOVE_EPSILON_SQ;
		}

		boolean shooting = shootingAtUs && config.rangedAttacksCountAsEngagement;
		if (moveCounts || mob.swinging || shooting) {
			lastActiveTick.put(id, now);
		}
	}

	// True when the mob has gotten meaningfully closer than the nearest distance it has
	// held within the recent window. Ratchets the anchor down as it approaches; if it
	// stalls or paces, the anchor is rebuilt after the window so movement stops counting.
	private boolean isNetApproaching(int id, long now, double distSq) {
		Long anchorTick = approachAnchorTick.get(id);
		Double anchor = approachAnchorSq.get(id);
		if (anchor == null || anchorTick == null || now - anchorTick > ACTIVE_WINDOW_TICKS) {
			approachAnchorSq.put(id, distSq);
			approachAnchorTick.put(id, now);
			return false;
		}
		double dist = Math.sqrt(distSq);
		double anchorDist = Math.sqrt(anchor);
		if (dist < anchorDist - APPROACH_MARGIN) {
			approachAnchorSq.put(id, distSq);
			approachAnchorTick.put(id, now);
			return true;
		}
		return false;
	}

	// True if the mob moved/attacked within the recent activity window. Swinging right
	// now always counts, so a mob meleeing you while you're cornered still holds.
	private boolean isActivelyEngaged(Mob mob, long now) {
		if (mob.swinging) return true;
		Long t = lastActiveTick.get(mob.getId());
		return t != null && (now - t) <= ACTIVE_WINDOW_TICKS;
	}

	private boolean isHeadAimedAtPlayer(Mob mob, LocalPlayer player, Vec3 playerEye) {
		double dx = player.getX() - mob.getX();
		double dz = player.getZ() - mob.getZ();
		// Minecraft yaw: 0 = +Z matches getYRot()/getYHeadRot().
		double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
		double yawDiff = Math.abs(Mth.wrapDegrees(targetYaw - mob.getYHeadRot()));
		if (yawDiff > config.headAimToleranceDegrees) return false;

		if (config.headAimChecksPitch) {
			// Reject a mob that can only crane near-straight up/down at us (we climbed out
			// of its reach). Minecraft pitch: negative = looking up, positive = looking down.
			Vec3 eye = mob.getEyePosition();
			double horiz = Math.sqrt(dx * dx + dz * dz);
			double dy = playerEye.y - eye.y;
			double targetPitch = -Math.toDegrees(Math.atan2(dy, Math.max(1.0e-4, horiz)));
			double pitchDiff = Math.abs(Mth.wrapDegrees(targetPitch - mob.getXRot()));
			if (pitchDiff > VERTICAL_AIM_TOLERANCE_DEG) return false;
		}
		return true;
	}

	private boolean hasLineOfSight(Mob mob, ClientLevel world, Vec3 playerEye) {
		Vec3 from = mob.getEyePosition();
		ClipContext ctx = new ClipContext(from, playerEye,
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob);
		return world.clip(ctx).getType() == HitResult.Type.MISS;
	}

	private static boolean isHostile(Mob mob) {
		// Enemy covers zombies, skeletons, creepers, spiders, piglins, etc.
		return mob instanceof Enemy;
	}
}
