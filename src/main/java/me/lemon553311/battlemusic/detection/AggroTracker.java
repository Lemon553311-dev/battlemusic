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
import java.util.Iterator;
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

	// entityId -> last tick it looked aggroed
	private final Map<Integer, Long> lastAggroTick = new HashMap<>();
	// entityId -> squared distance on the previous evaluation
	private final Map<Integer, Double> lastDistSq = new HashMap<>();
	// entityId -> position on the previous evaluation, to measure REAL movement
	private final Map<Integer, Vec3> lastPos = new HashMap<>();
	// entityId -> last tick the mob moved toward us, swung, or shot at us
	private final Map<Integer, Long> lastActiveTick = new HashMap<>();
	// closest distance held this window; movement only counts as engaged when it
	// beats this, so pacing/jittering at a steady distance stops sustaining music
	private final Map<Integer, Double> approachAnchorSq = new HashMap<>();
	private final Map<Integer, Long> approachAnchorTick = new HashMap<>();
	// entityId -> last tick a normally-neutral mob actually attacked us
	private final Map<Integer, Long> neutralCombatTick = new HashMap<>();

	// min movement (blocks/tick, squared) to read as "moving", filters interpolation jitter
	private static final double MOVE_EPSILON_SQ = 0.0025;
	// how long after its last real movement/attack a mob still counts as engaged
	private static final long ACTIVE_WINDOW_TICKS = 60L; // 3 seconds
	// must get this much closer than its window anchor for movement to read as approach
	private static final double APPROACH_MARGIN = 0.35;
	// reach within which a swinging neutral mob counts as attacking us
	private static final double NEUTRAL_REACH_SQ = 4.5 * 4.5;
	private static final long NEUTRAL_COMBAT_WINDOW_TICKS = 100L; // 5 seconds
	// hostile projectile this close means we are being shot at
	private static final double PROJECTILE_ENGAGE_NEAR_SQ = 8.0 * 8.0;
	// looser than yaw tolerance so ordinary height differences still count
	private static final double VERTICAL_AIM_TOLERANCE_DEG = 70.0;

	private int aggroCount = 0;
	// debug snapshot from the last evaluation
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

	// now is a monotonic client-tick counter from the state machine, NOT
	// world.getGameTime() - that freezes on time-locked servers (hypixel) and
	// stickiness would never expire there
	public void update(LocalPlayer player, ClientLevel world, long now) {
		if (player == null || world == null) {
			clear();
			return;
		}

		final double radius = config.detectionRadius;
		final double radiusSq = radius * radius;
		final long stickinessTicks = Math.round(config.aggroStickinessSeconds * 20.0);
		final Vec3 playerEye = eyePos(player);

		// track in-range mobs so stale ids of despawned mobs get pruned below
		final Set<Integer> inRangeNow = new HashSet<>();
		int aggroSignals = 0;

		// one entity sweep: collect in-range mobs and note who is shooting at us
		final AABB area = player.getBoundingBox().inflate(radius);
		final List<Mob> mobs = new ArrayList<>();
		final Set<Integer> rangedAttackerIds = new HashSet<>();
		for (Entity e : world.getEntities(player, area)) {
			if (e instanceof Mob) {
				Mob mob = (Mob) e;
				if (mob.distanceToSqr(player) <= radiusSq) mobs.add(mob);
			} else if (config.rangedAttacksCountAsEngagement && e instanceof Projectile) {
				Projectile proj = (Projectile) e;
				if (proj.distanceToSqr(player) <= PROJECTILE_ENGAGE_NEAR_SQ) {
					Entity owner = proj.getOwner();
					if (owner instanceof Mob) {
						Mob ownerMob = (Mob) owner;
						rangedAttackerIds.add(ownerMob.getId());
					}
				}
			}
		}

		for (Mob mob : mobs) {
			final int id = mob.getId();
			final double distSq = mob.distanceToSqr(player);
			final boolean enemy = isHostile(mob);
			final boolean shootingAtUs = rangedAttackerIds.contains(id);

			// normally-neutral mobs only count while actually attacking us, read from
			// synced combat actions (swing aimed at us / their projectile nearby), so a
			// calm animal wandering past never starts a battle
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

		// drop memory for anything that left the radius or despawned
		lastDistSq.keySet().retainAll(inRangeNow);
		lastPos.keySet().retainAll(inRangeNow);
		lastActiveTick.keySet().retainAll(inRangeNow);
		approachAnchorSq.keySet().retainAll(inRangeNow);
		approachAnchorTick.keySet().retainAll(inRangeNow);
		neutralCombatTick.keySet().retainAll(inRangeNow);

		// count entities still inside the stickiness window, prune the rest
		int count = 0;
		Iterator<Map.Entry<Integer, Long>> it = lastAggroTick.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, Long> entry = it.next();
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
		// explicit signals shortcut the heuristic: a fusing creeper / angry enderman /
		// warden is a real threat even standing still
		if (HostileStateSignals.isObviouslyAggressive(mob)) {
			return !config.requireLineOfSight || hasLineOfSight(mob, world, playerEye);
		}

		if (!isHeadAimedAtPlayer(mob, player, playerEye)) return false;
		if (config.requireActiveEngagement) {
			// only sustain while the mob is actually doing something; a lone mob just
			// standing there (stuck, out of reach, head-turning) can't hold the music
			if (!isActivelyEngaged(mob, now)) return false;
		} else {
			// legacy: any non-receding mob counts
			if (!closing) return false;
		}
		if (config.requireLineOfSight && !hasLineOfSight(mob, world, playerEye)) return false;
		return true;
	}

	// record whether the mob actually engaged this tick: a real approach, a melee
	// swing, or shooting at us. head-turning alone never refreshes this.
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
			// only a genuine net approach counts: must beat the closest distance held
			// this window, so pacing/circling at a steady distance doesn't
			moveCounts = moveSq > MOVE_EPSILON_SQ && isNetApproaching(id, now, distSq);
		} else {
			moveCounts = moveSq > MOVE_EPSILON_SQ;
		}

		boolean shooting = shootingAtUs && config.rangedAttacksCountAsEngagement;
		if (moveCounts || mob.swinging || shooting) {
			lastActiveTick.put(id, now);
		}
	}

	// true when the mob got meaningfully closer than the nearest distance held in the
	// recent window; the anchor ratchets down as it approaches
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

	// true if the mob moved/attacked within the activity window; swinging right now
	// always counts
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
			// reject a mob that can only crane near-straight up/down at us.
			// pitch: negative = looking up, positive = looking down
			Vec3 eye = eyePos(mob);
			double horiz = Math.sqrt(dx * dx + dz * dz);
			double dy = playerEye.y - eye.y;
			double targetPitch = -Math.toDegrees(Math.atan2(dy, Math.max(1.0e-4, horiz)));
			//? if >=1.17 {
			double pitchDiff = Math.abs(Mth.wrapDegrees(targetPitch - mob.getXRot()));
			//?} else {
			/*double pitchDiff = Math.abs(Mth.wrapDegrees(targetPitch - mob.xRot));
			*///?}
			if (pitchDiff > VERTICAL_AIM_TOLERANCE_DEG) return false;
		}
		return true;
	}

	private boolean hasLineOfSight(Mob mob, ClientLevel world, Vec3 playerEye) {
		Vec3 from = eyePos(mob);
		ClipContext ctx = new ClipContext(from, playerEye,
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob);
		return world.clip(ctx).getType() == HitResult.Type.MISS;
	}

	private static boolean isHostile(Mob mob) {
		return mob instanceof Enemy; // zombies, skeletons, creepers, spiders, piglins, ...
	}

	// getEyePosition() no-arg overload is 1.17+; 1.16.5 needs a partial-tick float
	//? if >=1.17 {
	private static Vec3 eyePos(Entity e) {
		return e.getEyePosition();
	}
	//?} else {
	/*private static Vec3 eyePos(Entity e) {
		return e.getEyePosition(1.0F);
	}
	*///?}
}
