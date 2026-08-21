package me.lemon553311.battlemusic.config;

// Fabric-only ModMenu entrypoint; the screen itself is in ClothConfigScreen.
// Only class-loaded when ModMenu is installed.

//? if fabric {
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import net.fabricmc.loader.api.FabricLoader;

public class ModMenuIntegration implements ModMenuApi {

	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		// guard or clicking Configure without Cloth Config crashes with
		// NoClassDefFoundError. (fabric id is "cloth-config2")
		if (!FabricLoader.getInstance().isModLoaded("cloth-config2")) {
			return screen -> null;
		}
		return ClothConfigScreen::build;
	}
}
//?} else {
/*public class ModMenuIntegration {
}
*///?}
