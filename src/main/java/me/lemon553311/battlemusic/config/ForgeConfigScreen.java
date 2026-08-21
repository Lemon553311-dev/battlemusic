package me.lemon553311.battlemusic.config;

// Registers the mods-list "Config" button on Forge (1.16.5 - 1.20.1).
// Extension point per version: CONFIGGUIFACTORY (1.16.5), ConfigGuiHandler in
// fmlclient (1.17) / client (1.18), ConfigScreenHandler (1.19+).
// Cloth Config is optional; both id spellings are checked since it has been
// seen as "cloth-config" and "cloth_config" across versions.

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
/*// deprecated-for-removal on newer Forge, but these old tiers are pinned forever
@SuppressWarnings({"deprecation", "removal"})
public final class ForgeConfigScreen {

	private ForgeConfigScreen() {}

	public static void register() {
		if (!ModList.get().isLoaded("cloth-config") && !ModList.get().isLoaded("cloth_config")) {
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
final class ForgeConfigScreen {
	private ForgeConfigScreen() {}
}
//?}
