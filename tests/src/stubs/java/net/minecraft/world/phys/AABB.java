package net.minecraft.world.phys;

/**
 * Test stand-in: production code only calls inflate() on boxes it is given,
 * so no geometry is modelled.
 */
public final class AABB {
	public AABB(double x0, double y0, double z0, double x1, double y1, double z1) {
	}

	public AABB inflate(double amount) {
		return this;
	}
}
