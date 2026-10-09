package net.minecraft.client.multiplayer;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Test stand-in: an explicit entity list (the AABB is accepted but not used
 * for filtering; tests control membership) and a configurable clip result.
 */
public class ClientLevel {
	private final List<Entity> entities = new ArrayList<>();
	private HitResult.Type clipType = HitResult.Type.MISS;

	public void addEntity(Entity e) {
		entities.add(e);
	}

	public void removeEntity(Entity e) {
		entities.remove(e);
	}

	public List<Entity> getEntities(Entity except, AABB box) {
		List<Entity> out = new ArrayList<>();
		for (Entity e : entities) {
			if (except != null && e.getId() == except.getId()) {
				continue;
			}
			out.add(e);
		}
		return out;
	}

	public Entity getEntity(int id) {
		for (Entity e : entities) {
			if (e.getId() == id) {
				return e;
			}
		}
		return null;
	}

	public HitResult clip(ClipContext ctx) {
		return new HitResult(clipType);
	}

	public void setClipType(HitResult.Type clipType) {
		this.clipType = clipType;
	}
}
