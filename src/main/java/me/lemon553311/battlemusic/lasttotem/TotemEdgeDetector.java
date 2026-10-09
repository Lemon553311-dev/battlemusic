package me.lemon553311.battlemusic.lasttotem;

/**
 * Damage-gated 2 -> 1 falling-edge detector behind the "Last Totem Standing"
 * alert. Pure logic, no Minecraft types: feed it the player's {@code hurtTime}
 * and total totem count every client tick; it returns true on ticks where the
 * alert should fire.
 *
 * <p>A real totem pop is always caused by damage (hurtTime rising edge), so a
 * 2 -> 1 drop with no damage near it is an inventory rearrangement (one totem
 * moved into a chest, shulker, ... by hand), not a pop. A drop with no damage
 * behind it waits a few ticks because the damage packet can land just after
 * the inventory sync.
 */
public final class TotemEdgeDetector {
	// ticks before the drop that still count as "caused by this damage"
	public static final long DAMAGE_BEFORE_TICKS = 20L;
	// ticks after the drop to still accept a late damage packet
	public static final long DAMAGE_AFTER_TICKS = 10L;

	private long tick = 0L;
	private int lastHurtTime = 0;
	private long lastDamageTick = Long.MIN_VALUE / 2;
	// a 2 -> 1 drop with no damage behind it waits for a late damage packet.
	// -1 = nothing pending.
	private long pendingDropTick = -1;
	// last sampled total totem count (-1 = not sampled yet)
	private int lastCount = -1;

	/**
	 * Advance one tick.
	 *
	 * @param hurtTime the player's current hurtTime field
	 * @param count    the current total totem count
	 * @return true if the alert should fire this tick
	 */
	public boolean update(int hurtTime, int count) {
		tick++;
		boolean fire = false;

		// rising edge on hurtTime = a damage instance landed (same signal the
		// PvP tracker uses; survives absorption/regen that hides health loss)
		if (hurtTime > lastHurtTime) {
			lastDamageTick = tick;
			// a drop seen just before this damage was its pop after all
			if (pendingDropTick >= 0 && tick - pendingDropTick <= DAMAGE_AFTER_TICKS) {
				pendingDropTick = -1;
				fire = true;
			}
		}
		lastHurtTime = hurtTime;

		if (lastCount < 0) {
			// first sample: baseline only
			lastCount = count;
			return fire;
		}

		// falling edge 2 -> 1. a totem use consumes exactly one totem, so any
		// bigger single-tick drop (2+ totems moved into a chest, shulker, etc.)
		// is an inventory rearrangement, not a totem being spent. and even an
		// exact 2 -> 1 is only a pop if damage landed just before it.
		if (lastCount == 2 && count == 1) {
			if (tick - lastDamageTick <= DAMAGE_BEFORE_TICKS) {
				pendingDropTick = -1;
				fire = true;
			} else {
				pendingDropTick = tick;
			}
		} else if (pendingDropTick >= 0 && (count != 1 || tick - pendingDropTick > DAMAGE_AFTER_TICKS)) {
			// count moved on (picked a totem back up) or no damage showed up:
			// it really was an inventory rearrangement
			pendingDropTick = -1;
		}
		lastCount = count;
		return fire;
	}

	/** Forget everything (feature disabled, player left, world unloaded). */
	public void reset() {
		lastCount = -1;
		pendingDropTick = -1;
	}
}
