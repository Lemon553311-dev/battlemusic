package net.minecraft.client.player;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/** Test stand-in: every client-visible damage/state field is settable. */
public class LocalPlayer extends Player {
	private float health = 20.0F;
	public int hurtTime = 0;
	public float fallDistance = 0.0F;
	private boolean onFire = false;
	private boolean inLava = false;
	private AABB boundingBox = new AABB(-1.0D, -1.0D, -1.0D, 1.0D, 1.0D, 1.0D);

	public float getHealth() {
		return health;
	}

	public void setHealth(float health) {
		this.health = health;
	}

	public boolean isOnFire() {
		return onFire;
	}

	public void setOnFire(boolean onFire) {
		this.onFire = onFire;
	}

	public boolean isInLava() {
		return inLava;
	}

	public void setInLava(boolean inLava) {
		this.inLava = inLava;
	}

	public AABB getBoundingBox() {
		return boundingBox;
	}
}
