package me.lemon553311.battlemusic.detection;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.warden.Warden;

/**
 * @author Lemon553311
 *
 * A few mob types leak their aggressive state through synced entity data, so we
 * can read them directly instead of guessing from movement. These are treated
 * as strong "definitely aggroed" signals.
 *
 * Mojang official mappings (Minecraft 26.1):
 *   Creeper.getSwellDir() (>0 while fusing) / Creeper.isIgnited()
 *   EnderMan.isCreepy() (provoked/aggressive)
 *   Warden (presence in range is enough)
 */

public final class HostileStateSignals {
	private HostileStateSignals() {}

	public static boolean isObviouslyAggressive(Mob mob) {
		// Creeper actively fusing toward an explosion.
		if (mob instanceof Creeper creeper) {
			if (creeper.getSwellDir() > 0 || creeper.isIgnited()) return true;
		}
		// Enderman angry state is synced for the screaming animation.
		if (mob instanceof EnderMan enderMan) {
			if (enderMan.isCreepy()) return true;
		}
		// Warden anger drives synced animations.
		if (mob instanceof Warden) {
			return true;
		}
		return false;
	}
}