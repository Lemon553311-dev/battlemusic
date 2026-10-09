package net.minecraft.util;

/** Test stand-in: faithful copy of vanilla wrapDegrees for float/double. */
public final class Mth {
	private Mth() {}

	public static float wrapDegrees(float value) {
		float f = value % 360.0F;
		if (f >= 180.0F) {
			f -= 360.0F;
		}
		if (f < -180.0F) {
			f += 360.0F;
		}
		return f;
	}

	public static double wrapDegrees(double value) {
		double d = value % 360.0D;
		if (d >= 180.0D) {
			d -= 360.0D;
		}
		if (d < -180.0D) {
			d += 360.0D;
		}
		return d;
	}
}
