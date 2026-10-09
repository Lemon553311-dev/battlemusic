package me.lemon553311.battlemusic;

// NeoForge (1.20.4 - 26.3) bootstrap; collapses to a placeholder on other
// loaders. (NeoForge 1.20.1 needs no target - it runs the Forge 1.20.1 jar.)
// Tick event split into Pre/Post at 1.20.5; client-only marking lives in the
// metadata (displayTest / clientSideOnly).

//? if neoforge {
/*import net.minecraft.client.Minecraft;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

import me.lemon553311.battlemusic.config.NeoForgeConfigScreen;
*///?}
//? if neoforge && >=1.20.5 {
/*import net.neoforged.neoforge.client.event.ClientTickEvent;
*///?} elif neoforge {
/*import net.neoforged.neoforge.event.TickEvent;
*///?}

//? if neoforge {
/*@Mod(BattleMusicClient.MOD_ID)
public final class BattleMusicNeoForge {

	public BattleMusicNeoForge(IEventBus modBus) {
*///?}
// FMLEnvironment.dist became getDist() at NeoForge 21.9
//? if neoforge && >=26.1 {
/*		if (FMLEnvironment.getDist() != Dist.CLIENT) return;
*///?} elif neoforge {
/*		if (FMLEnvironment.dist != Dist.CLIENT) return;
*///?}
//? if neoforge {
/*
		BattleMusicClient.init();

		modBus.addListener((FMLClientSetupEvent e) -> e.enqueueWork(BattleMusicClient::onClientStarted));

*///?}
//? if neoforge && >=1.20.5 {
		/*NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) ->
				BattleMusicClient.onEndClientTick(Minecraft.getInstance()));
*///?} elif neoforge {
		/*NeoForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent e) -> {
			if (e.phase == TickEvent.Phase.END) BattleMusicClient.onEndClientTick(Minecraft.getInstance());
		});
*///?}
//? if neoforge {
		/*NeoForge.EVENT_BUS.addListener(
				(ClientPlayerNetworkEvent.LoggingOut e) -> BattleMusicClient.onDisconnect());

		// no client-stopping event spans 1.20.4-26.3
		Runtime.getRuntime().addShutdownHook(
				new Thread(BattleMusicClient::onClientStopping, "battlemusic-shutdown"));

		NeoForgeConfigScreen.register();
	}
}
*///?} else {
final class BattleMusicNeoForge {
	private BattleMusicNeoForge() {}
}
//?}
