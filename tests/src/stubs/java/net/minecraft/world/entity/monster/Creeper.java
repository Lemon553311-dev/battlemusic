package net.minecraft.world.entity.monster;

import net.minecraft.world.entity.Mob;

/** Test stand-in. */
public class Creeper extends Mob implements Enemy {
	private int swellDir = 0;
	private boolean ignited = false;

	public int getSwellDir() {
		return swellDir;
	}

	public void setSwellDir(int swellDir) {
		this.swellDir = swellDir;
	}

	public boolean isIgnited() {
		return ignited;
	}

	public void setIgnited(boolean ignited) {
		this.ignited = ignited;
	}
}
