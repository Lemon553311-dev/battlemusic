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
 * Estimates how much damage the LOCAL PLAYER has recently RECEIVED FROM ANOTHER
 * PLAYER (PvP), using only client-visible data.
 *
 * The client never receives a "you were hit by player X for Y damage" event, so
 * we infer it. This is a CLIENT-SIDE mod: perfect attribution is impossible, so
 * the goal here is the "balanced" one -- count hits we can confidently pin on a
 * player, and tolerate the rare miss rather than firing on noise.
 *
 * How it works (the rewrite):
 *   1. "I got hit this tick" is detected from the rising edge of
 *      {@link LocalPlayer#hurtTime}, NOT from a raw health diff. hurtTime is set
 *      every time the player takes a damage instance, so this still fires when
 *      the hit was soaked by absorption hearts or instantly regenerated -- the
 *      old health-diff approach silently dropped those.
 *   2. The HP lost on that tick is the magnitude; if absorption/regen hid it we
 *      fall back to a nominal one-heart hit so the duel still registers.
 *   3. Attribution is latency-tolerant: instead of demanding an enemy swing on
 *      the exact damage tick (which lag almost always breaks), we continuously
 *      record the last tick a plausible enemy MELEE (player swinging, in reach,
 *      facing us) or RANGED (player-owned projectile right next to us) threat
 *      was seen, and accept a hit if one happened within {@link #LATENCY_TICKS}.
 *   4. Knockback corroboration: a melee hit shoves the player, so a sudden
 *      horizontal velocity backs up the melee case when the swing flag already
 *      cleared.
 *   5. Environmental veto: if fall/fire/lava is active this tick, only an
 *      immediate strong signal (player in reach now, projectile stuck in us, or
 *      clear knockback) is accepted -- so environmental damage taken near a
 *      bystander is not miscounted as PvP.
 *
 * When the rolling sum of attributed PvP damage within
 * {@code playerDamageWindowSeconds} crosses the threshold (default 6 HP, i.e.
 * 3 hearts) the trigger fires and the state machine forces heavy battle.
 *
 * Mojang official mappings (Minecraft 26.1): Player, Projectile.getOwner(),
 * LivingEntity.getHealth(), LivingEntity.hurtTime, Entity.getDeltaMovement(),
 * Level.getGameTime().
 */

public class PlayerDamageTracker {
	// Melee gates. Reach is padded over vanilla (~3) for latency/hitbox slop but
	// kept tight enough to reject bystanders. Aim tolerance is generous because an
	// attacker strafes mid-swing.
	private static final double MELEE_REACH = 4.5;
	private static final double AIM_TOLERANCE_DEG = 50.0;
	// A player-owned projectile this close counts as an impact (arrows are nearly
	// on top of you the tick they land).
	private static final double PROJECTILE_NEAR = 2.5;
	// Box we sweep for threats each tick. Small + cheap; big enough to catch a
	// fast arrow on its impact tick.
	private static final double SCAN_INFLATE = 6.0;
	// How many ticks a melee/projectile threat stays "fresh" for attribution,
	// covering the gap between the swing and the health drop we observe (~0.4s).
	private static final long LATENCY_TICKS = 8L;
	// Magnitude used when a hit landed (hurtTime edge) but absorption/regen hid the
	// HP change.
	private static final double NOMINAL_MASKED_HP = 1.0;
	// Clamp a single tick's attributed damage so one freak drop can't dominate.
	private static final double MAX_SINGLE_HIT_HP = 20.0;
	// Min horizontal speed^2 that we treat as REAL melee knockback. Kept well above
	// normal locomotion (sprinting is only ~0.017 speedSq) so that merely running past a
	// bystander who happens to be swinging can no longer corroborate a PvP hit; only an
	// actual knockback shove (~0.3+ blocks/tick) qualifies.
	private static final double KNOCKBACK_MIN_SPEED_SQ = 0.09;
	// Fall distance (blocks) above which we treat a hit as plausibly environmental.
	private static final double FALL_ENV_BLOCKS = 2.0;

	private final BattleMusicConfig config;

	// Rolling window of attributed PvP damage.
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

	// True if PvP damage was attributed to the player this tick
	public boolean receivedThisTick() {
		return receivedThisTick;
	}

	// PvP damage received within the rolling window, in HP
	public double getRecentDamageHp() {
		return recentDamage;
	}

	// {@code now} is a monotonic client-tick counter supplied by the state machine,
	// NOT world.getGameTime(). On servers that lock the time of day (Hypixel and many
	// other minigame/PvP servers) getGameTime() is frozen, which used to freeze this
	// rolling window so attributed damage never expired and the PvP trigger latched on
	// forever. A monotonic counter always advances, so the window drains correctly.
	public void update(LocalPlayer player, ClientLevel world, long now) {
		receivedThisTick = false;

		if (!config.playerDamageTriggerEnabled || player == null || world == null) {
			clear();
			return;
		}

		final float cur = player.getHealth();
		final int hurt = player.hurtTime;

		// First sighting: establish baselines, no detection yet.
		if (Float.isNaN(lastSelfHealth)) {
			lastSelfHealth = cur;
			lastHurtTime = hurt;
			return;
		}

		// Continuously watch for nearby enemy melee swings and player-owned
		// projectiles so a hit observed a tick or two late can still be attributed.
		observeThreats(player, world, now);

		// Rising edge on hurtTime => a new damage instance landed this tick. More
		// reliable than diffing health (survives absorption / instant regen).
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

		// Expire window entries older than the configured window.
		long windowTicks = Math.max(1L, Math.round(config.playerDamageWindowSeconds * 20.0));
		while (!window.isEmpty() && now - window.peekFirst().tick > windowTicks) {
			recentDamage -= window.pollFirst().amount;
		}
		if (recentDamage < 0.0) recentDamage = 0.0;

		triggered = recentDamage >= config.playerDamageThresholdHp;
	}

	// Record the most recent tick a credible enemy melee or ranged threat was seen.
	private void observeThreats(LocalPlayer self, ClientLevel world, long now) {
		final int selfId = self.getId();
		final double reachSq = MELEE_REACH * MELEE_REACH;
		final double projNearSq = PROJECTILE_NEAR * PROJECTILE_NEAR;
		final AABB area = self.getBoundingBox().inflate(SCAN_INFLATE);

		for (Entity e : world.getEntities(self, area)) {
			if (e.getId() == selfId) continue;

			if (e instanceof Player) {
				Player other = (Player) e;
				if (other.isAlive() && other.swinging
						&& other.distanceToSqr(self) <= reachSq
						&& facingToward(other, self)) {
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

	// Decide whether this tick's hit can be confidently pinned on a player.
	private boolean attributableToPlayer(LocalPlayer self, long now) {
		final boolean meleeRecent = (now - lastMeleeThreatTick) <= LATENCY_TICKS;
		final boolean projRecent = (now - lastProjectileThreatTick) <= LATENCY_TICKS;

		if (!meleeRecent && !projRecent) {
			return false; // no nearby player cause -> PvE/environment, don't guess
		}

		if (environmentalCauseActive(self)) {
			// Fall/fire/lava is in play: only an immediate, strong signal overrides it.
			boolean meleeNow = lastMeleeThreatTick == now;
			boolean projNow = lastProjectileThreatTick == now;
			boolean meleeByKnockback = meleeRecent && hasOutwardKnockback(self);
			return meleeNow || projNow || meleeByKnockback;
		}

		return true; // meleeRecent || projRecent, no competing environmental cause
	}

	// True if the attacker's head is roughly pointed at the player.
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
