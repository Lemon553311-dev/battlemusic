package net.minecraft.core.registries;

import net.minecraft.world.entity.EntityType;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Test stand-in for the entity-type registry. Tests map fake types to ids;
 * production code only calls {@code getKey(...).toString()}.
 */
public final class BuiltInRegistries {
	public static final FakeEntityTypeRegistry ENTITY_TYPE = new FakeEntityTypeRegistry();

	private BuiltInRegistries() {}

	public static final class FakeEntityTypeRegistry {
		private final Map<EntityType<?>, String> keys = new IdentityHashMap<>();

		public void register(EntityType<?> type, String id) {
			keys.put(type, id);
		}

		public void clear() {
			keys.clear();
		}

		public String getKey(EntityType<?> type) {
			String id = keys.get(type);
			return (id != null) ? id : "minecraft:unknown";
		}
	}
}
