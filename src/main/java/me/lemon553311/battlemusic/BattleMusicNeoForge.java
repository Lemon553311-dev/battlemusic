package me.lemon553311.battlemusic;

// NeoForge (1.20.4 - 26.2) bootstrap. On Fabric/Forge targets this whole file
// collapses to an unused placeholder class (see the trailing else).
// NeoForge 1.20.1 needs no target at all: it runs the Forge 1.20.1 jar
// unchanged (the fork only renamed packages at 1.20.2).
//
// Multi-version notes (Stonecutter //? directives, all flat - never nested):
//   - Client tick: TickEvent.ClientTickEvent (+ phase check) on 1.20.4;
//     split into ClientTickEvent.Pre/Post from 1.20.5 on.
//   - The mod constructor may take the mod event bus as a parameter
//     (supported on every tier here).
//   - Client-only marking is handled in the metadata: displayTest in
//     mods.toml on 1.20.4, clientSideOnly in neoforge.mods.toml on 1.20.5+.

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
		// Dedicated servers get nothing - this is a client-side mod.
		if (FMLEnvironment.dist != Dist.CLIENT) return;

		BattleMusicClient.init();

		// Audio engine boots once the client exists (mod bus).
		modBus.addListener((FMLClientSetupEvent e) -> e.enqueueWork(BattleMusicClient::onClientStarted));

		// Game bus: end-of-tick driving + disconnect reset.
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

		// No universal client-stopping event across 1.20.4-26.2; release the
		// OpenAL device/context from a JVM shutdown hook instead.
		Runtime.getRuntime().addShutdownHook(
				new Thread(BattleMusicClient::onClientStopping, "battlemusic-shutdown"));

		// "Config" button in the mods list (only when Cloth Config is installed).
		NeoForgeConfigScreen.register();
	}
}
*///?} else {
// Placeholder on non-NeoForge targets; the real class is NeoForge-gated above.
final class BattleMusicNeoForge {
	private BattleMusicNeoForge() {}
}
//?}
