package net.minecraft.world.entity.projectile;

import net.minecraft.world.entity.Entity;

/** Test stand-in: owner is settable. */
public class Projectile extends Entity {
	private Entity owner;

	public Entity getOwner() {
		return owner;
	}

	public void setOwner(Entity owner) {
		this.owner = owner;
	}
}
