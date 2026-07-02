package me.lemon553311.battlemusic.config;

// Registers the mods-list "Config" button on Forge (1.16.5 - 1.20.1), pointing
// at the same Cloth Config screen the Fabric ModMenu entry uses (see
// ClothConfigScreen). Cloth Config is a soft dependency: without it installed
// the button simply stays disabled and the mod runs fine.
//
// Multi-version notes (Stonecutter //? directives, all flat - never nested):
//   - 1.16.5: ExtensionPoint.CONFIGGUIFACTORY (BiFunction<Minecraft, Screen, Screen>).
//   - 1.17.1: ConfigGuiHandler.ConfigGuiFactory, in net.minecraftforge.fmlclient.
//   - 1.18.2: ConfigGuiHandler.ConfigGuiFactory, moved to net.minecraftforge.client.
//   - 1.19+ : renamed to ConfigScreenHandler.ConfigScreenFactory.
//   - Forge's mod id for Cloth Config is "cloth_config" (underscore), unlike
//     Fabric's "cloth-config".

//? if forge {
/*import me.lemon553311.battlemusic.BattleMusicClient;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
*///?}
//? if forge && >=1.19 {
/*import net.minecraftforge.client.ConfigScreenHandler;
*///?} elif forge && >=1.18 {
/*import net.minecraftforge.client.ConfigGuiHandler;
*///?} elif forge && >=1.17 {
/*import net.minecraftforge.fmlclient.ConfigGuiHandler;
*///?} elif forge {
/*import net.minecraftforge.fml.ExtensionPoint;
*///?}

//? if forge {
/*public final class ForgeConfigScreen {

	private ForgeConfigScreen() {}

	// Registers the mods-list "Config" button when Cloth Config is installed.
	public static void register() {
		if (!ModList.get().isLoaded("cloth_config")) {
			BattleMusicClient.LOGGER.info(
					"Cloth Config not installed - Battle Music's config screen is disabled "
					+ "(edit config/battlemusic.json directly instead)");
			return;
		}
*///?}
//? if forge && >=1.19 {
		/*ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
				() -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> ClothConfigScreen.build(parent)));
*///?} elif forge && >=1.17 {
		/*ModLoadingContext.get().registerExtensionPoint(ConfigGuiHandler.ConfigGuiFactory.class,
				() -> new ConfigGuiHandler.ConfigGuiFactory((mc, parent) -> ClothConfigScreen.build(parent)));
*///?} elif forge {
		/*ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.CONFIGGUIFACTORY,
				() -> (mc, parent) -> ClothConfigScreen.build(parent));
*///?}
//? if forge {
	/*}
}
*///?} else {
// Placeholder on non-Forge targets; the real class is Forge-gated above.
final class ForgeConfigScreen {
	private ForgeConfigScreen() {}
}
//?}
