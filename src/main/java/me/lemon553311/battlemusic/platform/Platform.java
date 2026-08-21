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
 * Loader-neutral access to the game/config directories. FMLPaths on
 * Forge/NeoForge (same class name, different package), FabricLoader on Fabric.
 */
public final class Platform {

	private Platform() {}

	/** .minecraft, where the battlemusic/ folder lives */
	public static Path gameDir() {
		//? if fabric {
		return FabricLoader.getInstance().getGameDir();
		//?} else {
		/*return FMLPaths.GAMEDIR.get();
		*///?}
	}

	/** config/, where battlemusic.json lives */
	public static Path configDir() {
		//? if fabric {
		return FabricLoader.getInstance().getConfigDir();
		//?} else {
		/*return FMLPaths.CONFIGDIR.get();
		*///?}
	}
}
