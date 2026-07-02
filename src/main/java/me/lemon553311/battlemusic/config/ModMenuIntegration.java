package me.lemon553311.battlemusic.config;

// ModMenu integration (Fabric only - ModMenu does not exist on Forge or
// NeoForge; those loaders register their mods-list Config button in
// ForgeConfigScreen / NeoForgeConfigScreen instead). The actual Cloth Config
// screen lives in ClothConfigScreen so every loader reuses the same UI.
//
// This class is ONLY loaded when ModMenu is installed (it is referenced
// through the "modmenu" entrypoint in fabric.mod.json), so the mod still runs
// fine without ModMenu / Cloth Config present.

//? if fabric {
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import net.fabricmc.loader.api.FabricLoader;

public class ModMenuIntegration implements ModMenuApi {

	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		// Cloth Config is optional. Without this guard, running ModMenu WITHOUT
		// Cloth Config crashed with a NoClassDefFoundError the moment the mod's
		// Configure button was clicked (ClothConfigScreen references Cloth Config
		// classes). Forge/NeoForge already had the equivalent guard in
		// ForgeConfigScreen / NeoForgeConfigScreen.
		// NOTE: the Fabric mod id is "cloth-config2" ("cloth_config" is the
		// Forge/NeoForge id checked by the other two loaders).
		if (!FabricLoader.getInstance().isModLoaded("cloth-config2")) {
			return screen -> null; // same as the ModMenuApi default: no config screen
		}
		return ClothConfigScreen::build;
	}
}
//?} else {
/*// Placeholder on non-Fabric targets; the real class is Fabric-gated above.
public class ModMenuIntegration {
}
*///?}
