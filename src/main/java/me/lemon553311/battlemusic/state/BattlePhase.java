package me.lemon553311.battlemusic.state;

/**
 * The three phases of the battle state machine.
 */
public enum BattlePhase {
	IDLE,
	REGULAR,
	HEAVY;

	public boolean isActive() {
		return this != IDLE;
	}
}
