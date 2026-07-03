package me.lemon553311.battlemusic.platform;

//? if fabric {
import net.fabricmc.loader.api.FabricLoader;
//?} elif forge {
/*import net.minecraftforge.fml.loading.FMLPaths;
*///?} else {
/*import net.neoforged.fml.loading.FMLPaths;
*///?}

import java.nio.file.Path;

/**
 * Loader-neutral access to the game/config directories.
 *
 * Multi-loader notes (Stonecutter //? directives):
 *   - Fabric exposes both through FabricLoader.
 *   - Forge and NeoForge both expose them through FMLPaths; only the package
 *     differs (net.minecraftforge.fml.loading before the NeoForge fork rename,
 *     net.neoforged.fml.loading after), so the method bodies are shared.
 */
public final class Platform {

	private Platform() {}

	/** The .minecraft (game) directory, where the battlemusic/ music folder lives. */
	public static Path gameDir() {
		//? if fabric {
		return FabricLoader.getInstance().getGameDir();
		//?} else {
		/*return FMLPaths.GAMEDIR.get();
		*///?}
	}

	/** The config/ directory, where battlemusic.json lives. */
	public static Path configDir() {
		//? if fabric {
		return FabricLoader.getInstance().getConfigDir();
		//?} else {
		/*return FMLPaths.CONFIGDIR.get();
		*///?}
	}
}
