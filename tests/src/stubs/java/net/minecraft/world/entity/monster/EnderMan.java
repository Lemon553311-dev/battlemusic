package net.minecraft.world.entity.monster;

import net.minecraft.world.entity.Mob;

/** Test stand-in. */
public class EnderMan extends Mob implements Enemy {
	private boolean creepy = false;

	public boolean isCreepy() {
		return creepy;
	}

	public void setCreepy(boolean creepy) {
		this.creepy = creepy;
	}
}
