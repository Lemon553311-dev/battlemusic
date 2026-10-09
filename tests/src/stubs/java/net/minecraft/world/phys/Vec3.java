package net.minecraft.world.phys;

/** Test stand-in: immutable 3D vector with the members production code uses. */
public final class Vec3 {
	public final double x;
	public final double y;
	public final double z;

	public Vec3(double x, double y, double z) {
		this.x = x;
		this.y = y;
		this.z = z;
	}

	public double lengthSqr() {
		return x * x + y * y + z * z;
	}
}
