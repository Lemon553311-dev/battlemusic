package net.minecraft.world.entity;

import net.minecraft.world.phys.Vec3;

/** Test stand-in: id, position, liveness, type and movement are settable. */
public class Entity {
	private static int nextId = 1;

	private final int id;
	public double x;
	public double y;
	public double z;
	private boolean alive = true;
	private EntityType<?> type;
	public Vec3 deltaMovement = new Vec3(0.0D, 0.0D, 0.0D);

	public Entity() {
		this.id = nextId++;
	}

	public int getId() {
		return id;
	}

	public double getX() {
		return x;
	}

	public double getY() {
		return y;
	}

	public double getZ() {
		return z;
	}

	public void setPos(double x, double y, double z) {
		this.x = x;
		this.y = y;
		this.z = z;
	}

	public boolean isAlive() {
		return alive;
	}

	public void setAlive(boolean alive) {
		this.alive = alive;
	}

	public EntityType<?> getType() {
		return type;
	}

	public void setType(EntityType<?> type) {
		this.type = type;
	}

	public double distanceToSqr(Entity other) {
		double dx = x - other.x;
		double dy = y - other.y;
		double dz = z - other.z;
		return dx * dx + dy * dy + dz * dz;
	}

	public Vec3 getEyePosition(float partialTick) {
		return new Vec3(x, y + 1.62D, z);
	}

	public Vec3 getDeltaMovement() {
		return deltaMovement;
	}
}
