package me.lemon553311.battlemusic.detection;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
//? if >=26.3 {
import net.minecraft.world.entity.monster.Enderman;
//?} else {
/*import net.minecraft.world.entity.monster.EnderMan;
*///?}
//? if >=1.19 {
import net.minecraft.world.entity.monster.warden.Warden;
//?}

/**
 * Mob types that leak their aggressive state through synced entity data -
 * read directly instead of guessing from movement.
 */

public final class HostileStateSignals {
	private HostileStateSignals() {}

	public static boolean isObviouslyAggressive(Mob mob) {
		// creeper actively fusing
		if (mob instanceof Creeper) {
			Creeper creeper = (Creeper) mob;
			if (creeper.getSwellDir() > 0 || creeper.isIgnited()) return true;
		}
		// enderman screaming = provoked
		//? if >=26.3 {
		if (mob instanceof Enderman) {
			Enderman enderMan = (Enderman) mob;
			if (enderMan.isCreepy()) return true;
		}
		//?} else {
		/*if (mob instanceof EnderMan) {
			EnderMan enderMan = (EnderMan) mob;
			if (enderMan.isCreepy()) return true;
		}
		*///?}
		//? if >=1.19 {
		if (mob instanceof Warden) {
			return true;
		}
		//?}
		return false;
	}
}