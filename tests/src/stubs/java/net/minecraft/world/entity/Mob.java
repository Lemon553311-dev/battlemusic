package net.minecraft.world.entity;

import net.minecraft.world.phys.Vec3;

/** Test stand-in: the swinging PUBLIC FIELD shape production code reads. */
public class Mob extends Entity {
	public boolean swinging = false;

	private float yHeadRot = 0.0F;
	private float xRot = 0.0F;

	public float getYHeadRot() {
		return yHeadRot;
	}

	public void setYHeadRot(float yHeadRot) {
		this.yHeadRot = yHeadRot;
	}

	public float getXRot() {
		return xRot;
	}

	public void setXRot(float xRot) {
		this.xRot = xRot;
	}

	public Vec3 position() {
		return new Vec3(x, y, z);
	}
}
