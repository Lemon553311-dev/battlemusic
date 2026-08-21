package me.lemon553311.battlemusic;

// Forge (1.16.5 - 1.20.1) bootstrap; collapses to a placeholder on other
// loaders. DisplayTest marks the mod client-only for server mod-list matching
// (API moved across versions, see gates). No client-stopping event spans this
// whole range, so OpenAL cleanup runs from a JVM shutdown hook.

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
@SuppressWarnings({"deprecation", "removal"}) // pinned old Forge versions
public final class BattleMusicForge {

	public BattleMusicForge() {
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
		if (FMLEnvironment.dist != Dist.CLIENT) return;

		BattleMusicClient.init();

		FMLJavaModLoadingContext.get().getModEventBus().addListener(
				(FMLClientSetupEvent e) -> e.enqueueWork(BattleMusicClient::onClientStarted));

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
		Runtime.getRuntime().addShutdownHook(
				new Thread(BattleMusicClient::onClientStopping, "battlemusic-shutdown"));

		ForgeConfigScreen.register();
	}
}
*///?} else {
final class BattleMusicForge {
	private BattleMusicForge() {}
}
//?}
