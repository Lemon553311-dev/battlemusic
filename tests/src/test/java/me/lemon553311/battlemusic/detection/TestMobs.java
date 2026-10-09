package me.lemon553311.battlemusic.detection;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;

/**
 * Shared fake mobs and aiming helpers for the detection suites.
 */
final class TestMobs {
	private TestMobs() {}

	/** Hostile mob (counts as Enemy, like zombies/skeletons). */
	static final class HostileMob extends Mob implements Enemy {
	}

	/** Normally-neutral mob (wolf-like): only counts while attacking. */
	static final class NeutralMob extends Mob {
	}

	static LocalPlayer playerAt(double x, double y, double z) {
		LocalPlayer p = new LocalPlayer();
		p.setPos(x, y, z);
		return p;
	}

	/** Point the mob's head at the player, mirroring the tracker's yaw math. */
	static void facePlayer(Mob mob, LocalPlayer player) {
		double dx = player.getX() - mob.getX();
		double dz = player.getZ() - mob.getZ();
		mob.setYHeadRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
	}

	static void facePlayer(net.minecraft.world.entity.player.Player from, LocalPlayer target) {
		double dx = target.getX() - from.getX();
		double dz = target.getZ() - from.getZ();
		from.setYHeadRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
	}
}
