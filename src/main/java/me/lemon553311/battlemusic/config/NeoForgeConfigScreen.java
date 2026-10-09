package me.lemon553311.battlemusic.config;

// Registers the mods-list "Config" button on NeoForge (1.20.4 - 26.3).
// 1.20.4: Forge-style ConfigScreenHandler.ConfigScreenFactory.
// 1.20.5+: IConfigScreenFactory - registered directly (not wrapped in a
// Supplier, the overloads are ambiguous for a nested lambda); the factory
// signature is (Minecraft, Screen) up to 1.21 and (ModContainer, Screen) after.

//? if neoforge {
/*import me.lemon553311.battlemusic.BattleMusicClient;

import net.neoforged.fml.ModList;
import net.neoforged.fml.ModLoadingContext;
*///?}
//? if neoforge && >=1.20.5 {
/*import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
*///?} elif neoforge {
/*import net.neoforged.neoforge.client.ConfigScreenHandler;
*///?}

//? if neoforge {
/*public final class NeoForgeConfigScreen {

	private NeoForgeConfigScreen() {}

	public static void register() {
		// both id spellings, same as on Forge
		if (!ModList.get().isLoaded("cloth-config") && !ModList.get().isLoaded("cloth_config")) {
			BattleMusicClient.LOGGER.info(
					"Cloth Config not installed - Battle Music's config screen is disabled "
					+ "(edit config/battlemusic.json directly instead)");
			return;
		}
*///?}
//? if neoforge && >=1.21 {
		/*ModLoadingContext.get().getActiveContainer().registerExtensionPoint(
				IConfigScreenFactory.class, (container, parent) -> ClothConfigScreen.build(parent));
*///?} elif neoforge && >=1.20.5 {
		/*ModLoadingContext.get().getActiveContainer().registerExtensionPoint(
				IConfigScreenFactory.class, (IConfigScreenFactory) (minecraft, parent) -> ClothConfigScreen.build(parent));
*///?} elif neoforge {
		/*ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
				() -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> ClothConfigScreen.build(parent)));
*///?}
//? if neoforge {
/*}
}
*///?} else {
final class NeoForgeConfigScreen {
	private NeoForgeConfigScreen() {}
}
//?}
