package net.minecraft.world.entity.player;

import net.minecraft.world.entity.Entity;

/** Test stand-in: swinging is a public field, like vanilla. */
public class Player extends Entity {
	public boolean swinging = false;

	private float yHeadRot = 0.0F;

	public float getYHeadRot() {
		return yHeadRot;
	}

	public void setYHeadRot(float yHeadRot) {
		this.yHeadRot = yHeadRot;
	}
}
