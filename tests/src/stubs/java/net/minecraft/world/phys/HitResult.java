package net.minecraft.world.phys;

/** Test stand-in: tests choose MISS (clear shot) or BLOCK (obstructed). */
public class HitResult {
	public enum Type {
		MISS,
		BLOCK
	}

	private final Type type;

	public HitResult(Type type) {
		this.type = type;
	}

	public Type getType() {
		return type;
	}
}
