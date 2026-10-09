package net.minecraft.world.level;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Test stand-in: only the constructor shape and enum names matter. */
public class ClipContext {
	public enum Block {
		COLLIDER
	}

	public enum Fluid {
		NONE
	}

	public ClipContext(Vec3 from, Vec3 to, Block block, Fluid fluid, Entity entity) {
	}
}
