package me.lemon553311.battlemusic.detection;

import me.lemon553311.battlemusic.BattleMusicClient;
import me.lemon553311.battlemusic.config.BattleMusicConfig;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Estimates how much damage the local player recently received FROM another
 * player (PvP), using only client-visible data - the client never gets a
 * "hit by player X" event, so this infers it and tolerates the rare miss
 * rather than firing on noise.
 */

public class PlayerDamageTracker {
	// padded over vanilla (~3) for latency/hitbox slop, tight enough to reject bystanders
	private static final double MELEE_REACH = 4.5;
	// generous: an attacker strafes mid-swing
	private static final double AIM_TOLERANCE_DEG = 50.0;
	// player-owned projectile this close counts as an impact
	private static final double PROJECTILE_NEAR = 2.5;
	// threat sweep box; big enough to catch a fast arrow on its impact tick
	private static final double SCAN_INFLATE = 6.0;
	// how long a melee/projectile threat stays fresh for attribution (~0.4s of lag)
	private static final long LATENCY_TICKS = 8L;
	// HP used when a hit landed (hurtTime edge) but absorption/regen hid the change
	private static final double NOMINAL_MASKED_HP = 1.0;
	private static final double MAX_SINGLE_HIT_HP = 20.0;
	// min horizontal speed^2 treated as real knockback, well above sprinting (~0.017)
	// so running past a swinging bystander doesn't corroborate a hit
	private static final double KNOCKBACK_MIN_SPEED_SQ = 0.09;
	// fall distance above which a hit is plausibly environmental
	private static final double FALL_ENV_BLOCKS = 2.0;

	private final BattleMusicConfig config;

	// rolling window of attributed pvp damage
	private final Deque<DmgEvent> window = new ArrayDeque<>();

	private float lastSelfHealth = Float.NaN;
	private int lastHurtTime = 0;
	private long lastMeleeThreatTick = Long.MIN_VALUE / 2;
	private long lastProjectileThreatTick = Long.MIN_VALUE / 2;
	private int lastMeleeAttackerId = -1;
	private int lastProjectileAttackerId = -1;

	private double recentDamage = 0.0; // sum of window amounts, in HP
	private boolean triggered = false; // recentDamage >= threshold
	private boolean receivedThisTick = false; // PvP damage attributed this tick

	public PlayerDamageTracker(BattleMusicConfig config) {
		this.config = config;
	}

	public boolean isTriggered() {
		return triggered;
	}

	// true if pvp damage was attributed this tick
	public boolean receivedThisTick() {
		return receivedThisTick;
	}

	// pvp damage received within the rolling window, in HP
	public double getRecentDamageHp() {
		return recentDamage;
	}

	// now is a monotonic client-tick counter, NOT world.getGameTime() - that is
	// frozen on time-locked servers (hypixel etc.), which latched the window on forever
	public void update(LocalPlayer player, ClientLevel world, long now) {
		receivedThisTick = false;

		if (!config.playerDamageTriggerEnabled || player == null || world == null) {
			clear();
			return;
		}

		final float cur = player.getHealth();
		final int hurt = player.hurtTime;

		// first sighting: baselines only, no detection
		if (Float.isNaN(lastSelfHealth)) {
			lastSelfHealth = cur;
			lastHurtTime = hurt;
			return;
		}

		// continuously watch for nearby melee swings / player projectiles so a hit
		// observed a tick or two late can still be attributed
		observeThreats(player, world, now);

		// rising edge on hurtTime = a damage instance landed this tick. more reliable
		// than diffing health (survives absorption / instant regen)
		final boolean freshHit = hurt > lastHurtTime;
		final float drop = Math.max(0f, lastSelfHealth - cur);

		if (freshHit && attributableToPlayer(player, now)) {
			double amount = drop > 0.01f ? drop : NOMINAL_MASKED_HP;
			amount = Math.min(amount, MAX_SINGLE_HIT_HP);
			addDamage(now, amount);
			receivedThisTick = true;
			BattleMusicClient.debug("PvP hit attributed: {} HP (melee#{} proj#{}) -> window {} HP",
					String.format(java.util.Locale.ROOT, "%.1f", amount),
					lastMeleeAttackerId, lastProjectileAttackerId,
					String.format(java.util.Locale.ROOT, "%.1f", recentDamage));
		}

		lastSelfHealth = cur;
		lastHurtTime = hurt;

		// expire window entries older than the configured window
		long windowTicks = Math.max(1L, Math.round(config.playerDamageWindowSeconds * 20.0));
		while (!window.isEmpty() && now - window.peekFirst().tick > windowTicks) {
			recentDamage -= window.pollFirst().amount;
		}
		if (recentDamage < 0.0) recentDamage = 0.0;

		triggered = recentDamage >= config.playerDamageThresholdHp;
	}

	// record the most recent tick a credible enemy melee or ranged threat was seen
	private void observeThreats(LocalPlayer self, ClientLevel world, long now) {
		final int selfId = self.getId();
		final double reachSq = MELEE_REACH * MELEE_REACH;
		final double projNearSq = PROJECTILE_NEAR * PROJECTILE_NEAR;
		final AABB area = self.getBoundingBox().inflate(SCAN_INFLATE);

		for (Entity e : world.getEntities(self, area)) {
			if (e.getId() == selfId) continue;

			if (e instanceof Player) {
				Player other = (Player) e;
				//? if >=26.3 {
				if (other.isAlive() && other.isSwinging()
						&& other.distanceToSqr(self) <= reachSq
						&& facingToward(other, self)) {
				//?} else {
				/*if (other.isAlive() && other.swinging
						&& other.distanceToSqr(self) <= reachSq
						&& facingToward(other, self)) {
				*///?}
					lastMeleeThreatTick = now;
					lastMeleeAttackerId = other.getId();
				}
				continue;
			}

			if (e instanceof Projectile) {
				Projectile proj = (Projectile) e;
				Entity owner = proj.getOwner();
				if (owner instanceof Player) {
					Player p = (Player) owner;
					if (p.getId() != selfId && proj.distanceToSqr(self) <= projNearSq) {
						lastProjectileThreatTick = now;
						lastProjectileAttackerId = p.getId();
					}
				}
			}
		}
	}

	// decide whether this tick's hit can be pinned on a player
	private boolean attributableToPlayer(LocalPlayer self, long now) {
		final boolean meleeRecent = (now - lastMeleeThreatTick) <= LATENCY_TICKS;
		final boolean projRecent = (now - lastProjectileThreatTick) <= LATENCY_TICKS;

		if (!meleeRecent && !projRecent) {
			return false; // no nearby player cause -> pve/environment, don't guess
		}

		if (environmentalCauseActive(self)) {
			// fall/fire/lava in play: only an immediate strong signal overrides it
			boolean meleeNow = lastMeleeThreatTick == now;
			boolean projNow = lastProjectileThreatTick == now;
			boolean meleeByKnockback = meleeRecent && hasOutwardKnockback(self);
			return meleeNow || projNow || meleeByKnockback;
		}

		return true;
	}

	// true if the attacker's head is roughly pointed at the player
	private boolean facingToward(Player from, LocalPlayer target) {
		double dx = target.getX() - from.getX();
		double dz = target.getZ() - from.getZ();
		if (dx * dx + dz * dz < 1.0e-6) return true; // basically on top of us
		double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
		double diff = Math.abs(Mth.wrapDegrees(targetYaw - from.getYHeadRot()));
		return diff <= AIM_TOLERANCE_DEG;
	}

	private boolean hasOutwardKnockback(LocalPlayer self) {
		Vec3 v = self.getDeltaMovement();
		return (v.x * v.x + v.z * v.z) >= KNOCKBACK_MIN_SPEED_SQ;
	}

	private boolean environmentalCauseActive(LocalPlayer self) {
		return self.fallDistance > FALL_ENV_BLOCKS || self.isOnFire() || self.isInLava();
	}

	private void addDamage(long tick, double amount) {
		window.addLast(new DmgEvent(tick, amount));
		recentDamage += amount;
	}

	public void clear() {
		window.clear();
		recentDamage = 0.0;
		triggered = false;
		receivedThisTick = false;
		lastSelfHealth = Float.NaN;
		lastHurtTime = 0;
		lastMeleeThreatTick = Long.MIN_VALUE / 2;
		lastProjectileThreatTick = Long.MIN_VALUE / 2;
		lastMeleeAttackerId = -1;
		lastProjectileAttackerId = -1;
	}

	private static final class DmgEvent {
		final long tick;
		final double amount;

		DmgEvent(long tick, double amount) {
			this.tick = tick;
			this.amount = amount;
		}
	}
}
