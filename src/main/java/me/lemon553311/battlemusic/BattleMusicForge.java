package me.lemon553311.battlemusic;

// Forge (1.16.5 - 1.20.1) bootstrap. On Fabric/NeoForge targets this whole
// file collapses to an unused placeholder class (see the trailing else).
//
// Multi-version notes (Stonecutter //? directives, all flat - never nested,
// because Java block comments cannot nest):
//   - DisplayTest ("this mod is client-only, ignore it for server matching"):
//     ExtensionPoint.DISPLAYTEST + FMLNetworkConstants on 1.16.5,
//     IExtensionPoint.DisplayTest + fmllegacy FMLNetworkConstants on 1.17,
//     IExtensionPoint.DisplayTest + NetworkConstants on 1.18+.
//   - Disconnect event: ClientPlayerNetworkEvent.LoggedOutEvent (<1.19) was
//     renamed to ClientPlayerNetworkEvent.LoggingOut (>=1.19).
//   - There is no client-stopping event that spans 1.16.5-1.20.1, so OpenAL
//     cleanup runs from a JVM shutdown hook instead.

//? if forge {
/*import net.minecraft.client.Minecraft;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

import me.lemon553311.battlemusic.config.ForgeConfigScreen;
*///?}
//? if forge && >=1.18 {
/*import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.network.NetworkConstants;
*///?} elif forge && >=1.17 {
/*import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fmllegacy.network.FMLNetworkConstants;
*///?} elif forge {
/*import net.minecraftforge.fml.ExtensionPoint;
import net.minecraftforge.fml.network.FMLNetworkConstants;
import org.apache.commons.lang3.tuple.Pair;
*///?}

//? if forge {
/*@Mod(BattleMusicClient.MOD_ID)
// ModLoadingContext.get() and FMLJavaModLoadingContext.get() are marked
// deprecated-for-removal on newer Forge, but they are the only available APIs
// on the old, exactly-pinned Forge versions this file targets (1.16.5-1.20.1),
// which will never be bumped forward - suppressed rather than left as noise.
@SuppressWarnings({"deprecation", "removal"})
public final class BattleMusicForge {

	public BattleMusicForge() {
		// Client-only mod: tell Forge to ignore it when matching server mod
		// lists, so joining vanilla / dedicated servers keeps working.
*///?}
//? if forge && >=1.18 {
		/*ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
				() -> new IExtensionPoint.DisplayTest(() -> NetworkConstants.IGNORESERVERONLY, (remote, isServer) -> true));
*///?} elif forge && >=1.17 {
		/*ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
				() -> new IExtensionPoint.DisplayTest(() -> FMLNetworkConstants.IGNORESERVERONLY, (remote, isServer) -> true));
*///?} elif forge {
		/*ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.DISPLAYTEST,
				() -> Pair.of(() -> FMLNetworkConstants.IGNORESERVERONLY, (remote, isServer) -> true));
*///?}
//? if forge {
/*
		// Dedicated servers get nothing else - this is a client-side mod.
		if (FMLEnvironment.dist != Dist.CLIENT) return;

		BattleMusicClient.init();

		// Audio engine boots once the client exists (mod bus).
		FMLJavaModLoadingContext.get().getModEventBus().addListener(
				(FMLClientSetupEvent e) -> e.enqueueWork(BattleMusicClient::onClientStarted));

		// Game bus: end-of-tick driving + disconnect reset.
		MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent e) -> {
			if (e.phase == TickEvent.Phase.END) BattleMusicClient.onEndClientTick(Minecraft.getInstance());
		});
*///?}
//? if forge && >=1.19 {
		/*MinecraftForge.EVENT_BUS.addListener(
				(ClientPlayerNetworkEvent.LoggingOut e) -> BattleMusicClient.onDisconnect());
*///?} elif forge {
		/*MinecraftForge.EVENT_BUS.addListener(
				(ClientPlayerNetworkEvent.LoggedOutEvent e) -> BattleMusicClient.onDisconnect());
*///?}
//? if forge {
/*
		// No CLIENT_STOPPING equivalent spans 1.16.5-1.20.1; release the OpenAL
		// device/context from a JVM shutdown hook instead.
		Runtime.getRuntime().addShutdownHook(
				new Thread(BattleMusicClient::onClientStopping, "battlemusic-shutdown"));

		// "Config" button in the mods list (only when Cloth Config is installed).
		ForgeConfigScreen.register();
	}
}
*///?} else {
// Placeholder on non-Forge targets; the real class is Forge-gated above.
final class BattleMusicForge {
	private BattleMusicForge() {}
}
//?}
