package me.lemon553311.battlemusic.config;

// Registers the mods-list "Config" button on NeoForge (1.20.4 - 26.2),
// pointing at the same Cloth Config screen the Fabric ModMenu entry uses (see
// ClothConfigScreen). Cloth Config is a soft dependency: without it installed
// the button simply stays disabled and the mod runs fine.
//
// Multi-version notes (Stonecutter //? directives, all flat - never nested):
//   - 1.20.4: still the Forge-style ConfigScreenHandler.ConfigScreenFactory
//     (in net.neoforged.neoforge.client).
//   - 1.20.5-1.20.6: IConfigScreenFactory (net.neoforged.neoforge.client.gui),
//     registered directly (not wrapped in a Supplier - ModContainer overloads
//     registerExtensionPoint(Class<T>,T) and registerExtensionPoint(Class<T>,
//     Supplier<T>), and a zero-arg lambda around the factory lambda is
//     genuinely ambiguous between them); javadoc-confirmed signature
//     createScreen(Minecraft, Screen).
//   - 1.21+: IConfigScreenFactory's method became
//     createScreen(ModContainer, Screen), registered as a direct instance
//     (this is the shape the 21.x ConfigurationScreen::new example requires).

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

	// Registers the mods-list "Config" button when Cloth Config is installed.
	public static void register() {
		// Cloth Config's mod id is "cloth-config" (hyphen), same as on Forge -
		// the old "cloth_config" (underscore) check never matched, silently
		// disabling the Config button. Both spellings checked defensively.
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
// Placeholder on non-NeoForge targets; the real class is NeoForge-gated above.
final class NeoForgeConfigScreen {
	private NeoForgeConfigScreen() {}
}
//?}
