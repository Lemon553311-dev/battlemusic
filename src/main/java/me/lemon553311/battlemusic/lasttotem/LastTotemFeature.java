package me.lemon553311.battlemusic.lasttotem;

import me.lemon553311.battlemusic.BattleMusicClient;
import me.lemon553311.battlemusic.config.BattleMusicConfig;

//? if fabric {
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
//?}
//? if fabric && >=1.21.6 {
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
//?} elif fabric {
/*import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
*///?} elif forge && >=1.19 {
/*import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
*///?} elif forge {
/*import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
*///?} elif neoforge && >=1.20.5 {
/*import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
*///?} elif neoforge {
/*import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TickEvent;
*///?}

import net.minecraft.client.Minecraft;
//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} elif >=1.20 {
/*import net.minecraft.client.gui.GuiGraphics;
*///?} else {
/*import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
*///?}
import net.minecraft.client.player.LocalPlayer;
//? if >=1.21.6 {
import net.minecraft.client.renderer.RenderPipelines;
//?} elif >=1.21.2 {
/*import net.minecraft.client.renderer.RenderType;
*///?}
//? if >=26.1 {
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * "Last Totem Standing" - a secret, password-gated alert.
 *
 * When the feature is unlocked (see the mod menu) it watches the player's totem
 * count every client tick. The moment that count falls to exactly ONE remaining
 * it plays a bundled alert sound and flashes an image in the centre of the
 * screen, fading 20% -> 70% over 3s, then 70% -> 0% over 1s.
 *
 * Multi-version notes (Stonecutter //? directives below):
 *   - HUD registration: HudElementRegistry on 1.21.6+, HudRenderCallback before.
 *   - Class names: Identifier/GuiGraphicsExtractor on 26.1+, ResourceLocation/
 *     GuiGraphics on 1.20-1.21.x, ResourceLocation/PoseStack+GuiComponent
 *     before 1.20.
 *   - Draw call: RenderPipelines blit overload on 1.21.5+, the legacy scaled
 *     GuiGraphics blit + RenderSystem tint on 1.20-1.21.4, the PoseStack +
 *     GuiComponent static blit helper (with a manual texture bind) before 1.20.
 * Everything else (totem counting, tick logic, audio) is version-agnostic.
 */
public final class LastTotemFeature {

	// good job you found the password to this not so hidden feature. Have fun!
	// Input it in the "Advanced" tab in the modmenu (or just enable it in the config lmao)
	public static final String PASSWORD = "lmao";

	// Bundled at assets/battlemusic/textures/gui/last_totem_standing.png
	//? if >=26.1 {
	private static final Identifier IMAGE = mkId("textures/gui/last_totem_standing.png");
	//?} else {
	/*private static final ResourceLocation IMAGE = mkId("textures/gui/last_totem_standing.png");
	*///?}
	// Native pixel size of that PNG, used for aspect-correct scaling.
	private static final int IMG_W = 1023;
	private static final int IMG_H = 667;

	// Opacity animation: 0.20 -> 0.70 over PHASE1, then 0.70 -> 0.00 over PHASE2.
	private static final double PHASE1_SECONDS = 3.0;
	private static final double PHASE2_SECONDS = 1.0;
	private static final float ALPHA_START = 0.20f;
	private static final float ALPHA_PEAK = 0.70f;
	private static final float ALPHA_END = 0.00f;

	// Inset from every screen edge -> the image is centred in the remaining box.
	private static final float EDGE_INSET = 0.30f;

	private final BattleMusicConfig config;

	// Tick-thread state: last sampled total totem count (-1 = not sampled yet).
	private int lastTotemCount = -1;

	// Animation state: written on the client-tick thread, read on the render thread.
	private volatile boolean animActive = false;
	private volatile long animStartNanos = 0L;

	public LastTotemFeature(BattleMusicConfig config) {
		this.config = config;
	}

	public void init() {
		// Tick source per loader; the totem-counting logic itself is shared
		// (onClientTick) and loader-neutral.
		//? if fabric {
		ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
		//?} elif forge {
		/*MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent e) -> {
			if (e.phase == TickEvent.Phase.END) onClientTick(Minecraft.getInstance());
		});
		*///?} elif neoforge && >=1.20.5 {
		/*NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> onClientTick(Minecraft.getInstance()));
		*///?} else {
		/*NeoForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent e) -> {
			if (e.phase == TickEvent.Phase.END) onClientTick(Minecraft.getInstance());
		});
		*///?}

		// HUD hook per loader/version; the drawing itself is shared (onHudRender).
		//? if fabric && >=1.21.6 {
		// HudRenderCallback no longer exists in 1.21.6+. Register a HUD element
		// instead; it draws right before the chat layer.
		HudElementRegistry.attachElementBefore(
				VanillaHudElements.CHAT,
				mkId("last_totem_standing"),
				(graphics, delta) -> onHudRender(graphics));
		//?} elif fabric {
		/*HudRenderCallback.EVENT.register((graphics, tickDelta) -> onHudRender(graphics));
		*///?} elif forge && >=1.20 {
		/*MinecraftForge.EVENT_BUS.addListener((RenderGuiEvent.Post e) -> onHudRender(e.getGuiGraphics()));
		*///?} elif forge && >=1.19 {
		/*MinecraftForge.EVENT_BUS.addListener((RenderGuiEvent.Post e) -> onHudRender(e.getPoseStack()));
		*///?} elif forge {
		/*MinecraftForge.EVENT_BUS.addListener((RenderGameOverlayEvent.Post e) -> {
			if (e.getType() == RenderGameOverlayEvent.ElementType.ALL) onHudRender(e.getMatrixStack());
		});
		*///?} elif neoforge {
		/*NeoForge.EVENT_BUS.addListener((RenderGuiEvent.Post e) -> onHudRender(e.getGuiGraphics()));
		*///?}
	}

	private void onClientTick(Minecraft client) {
		if (config == null || !config.lastTotemEnabled) {
			lastTotemCount = -1;
			return;
		}
		LocalPlayer player = client.player;
		if (player == null || client.level == null) {
			lastTotemCount = -1;
			return;
		}

		int count = countTotems(player);
		if (lastTotemCount < 0) {
			// First sample after enabling / joining a world: set the baseline only.
			lastTotemCount = count;
			return;
		}

		// Falling edge to exactly one remaining: had two (or more), one just popped.
		if (lastTotemCount >= 2 && count == 1) {
			trigger(client);
		}
		lastTotemCount = count;
	}

	private int countTotems(LocalPlayer player) {
		//? if >=1.17 {
		Inventory inv = player.getInventory();
		//?} else {
		/*Inventory inv = player.inventory;
		*///?}
		int total = 0;
		// The Inventory container spans hotbar + main + armor + offhand in vanilla.
		int size = inv.getContainerSize();
		for (int i = 0; i < size; i++) {
			if (isTotem(inv.getItem(i))) {
				total += inv.getItem(i).getCount();
			}
		}
		// A totem held on the mouse cursor lives on the open menu, not the Inventory
		// container, so count it here too to avoid a false dip to 1.
		if (player.containerMenu != null) {
			//? if >=1.17 {
			ItemStack carried = player.containerMenu.getCarried();
			//?} else {
			/*ItemStack carried = player.inventory.getCarried();
			*///?}
			if (isTotem(carried)) {
				total += carried.getCount();
			}
		}
		return total;
	}

	private static boolean isTotem(ItemStack stack) {
		return !stack.isEmpty() && stack.getItem() == Items.TOTEM_OF_UNDYING;
	}

	private void trigger(Minecraft client) {
		BattleMusicClient.debug("Last Totem Standing: one totem remaining -> firing alert");
		// (Re)start the overlay animation from the top.
		animStartNanos = System.nanoTime();
		animActive = true;
		// Play the alert at the player's master volume, on the mod's own audio path.
		float master = client.options.getSoundSourceVolume(SoundSource.MASTER);
		if (master > 0.0001f) {
			OneShotSound.play(master);
		}
	}

	//? if >=26.1 {
	private void onHudRender(GuiGraphicsExtractor graphics) {
	//?} elif >=1.20 {
	/*private void onHudRender(GuiGraphics graphics) {
	*///?} else {
	/*private void onHudRender(PoseStack matrices) {
	*///?}
		if (!animActive) return;

		double elapsed = (System.nanoTime() - animStartNanos) / 1_000_000_000.0;
		if (elapsed >= PHASE1_SECONDS + PHASE2_SECONDS) {
			animActive = false;
			return;
		}
		float alpha = alphaFor(elapsed);
		if (alpha <= 0.0f) return;

		Minecraft mc = Minecraft.getInstance();
		int screenW = mc.getWindow().getGuiScaledWidth();
		int screenH = mc.getWindow().getGuiScaledHeight();

		// 30% inset from each side -> central box is 40% wide and 40% tall.
		int boxX = Math.round(screenW * EDGE_INSET);
		int boxY = Math.round(screenH * EDGE_INSET);
		int boxW = screenW - 2 * boxX;
		int boxH = screenH - 2 * boxY;
		if (boxW <= 0 || boxH <= 0) return;

		// Fit the image inside that box, preserving aspect ratio.
		float scale = Math.min(boxW / (float) IMG_W, boxH / (float) IMG_H);
		int drawW = Math.max(1, Math.round(IMG_W * scale));
		int drawH = Math.max(1, Math.round(IMG_H * scale));
		int drawX = boxX + (boxW - drawW) / 2;
		int drawY = boxY + (boxH - drawH) / 2;

		int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
		int color = (a << 24) | 0x00FFFFFF; // white tint, animated alpha (ARGB)

		//? if >=1.21.6 {
		// 1.21.6+ blit: (pipeline, texture, x, y, u, v, drawW, drawH,
		//                regionW, regionH, texW, texH, argbColor).
		graphics.blit(
				RenderPipelines.GUI_TEXTURED,
				IMAGE,
				drawX, drawY,
				0, 0,
				drawW, drawH,
				IMG_W, IMG_H,
				IMG_W, IMG_H,
				color);
		//?} elif >=1.21.2 {
		/*// 1.21.2-1.21.5 blit: same 13-arg shape, but the first parameter is a
		// Function<ResourceLocation, RenderType> (RenderType::guiTextured) rather
		// than a RenderPipeline. The RenderPipeline overload only exists in 1.21.6+.
		graphics.blit(
				RenderType::guiTextured,
				IMAGE,
				drawX, drawY,
				0, 0,
				drawW, drawH,
				IMG_W, IMG_H,
				IMG_W, IMG_H,
				color);
		*///?} elif >=1.20 {
		/*// Legacy (1.20-1.21.4) scaled blit: tint via RenderSystem shader color.
		// VERIFY on build - see PORTING.md for the exact blit signature per
		// version if this does not resolve on 1.20.1 / 1.21.0-1.21.4.
		com.mojang.blaze3d.systems.RenderSystem.enableBlend();
		com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
		graphics.blit(
				IMAGE,
				drawX, drawY,
				drawW, drawH,
				0f, 0f,
				IMG_W, IMG_H,
				IMG_W, IMG_H);
		com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		com.mojang.blaze3d.systems.RenderSystem.disableBlend();
		*///?} elif >=1.17 {
		/*// 1.17-1.19.x: GuiGraphics does not exist yet, but the 1.17 RenderSystem
		// shader API (setShaderTexture/setShaderColor) does. Draw via the PoseStack
		// + GuiComponent static blit helper, tinting through the shader color.
		com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(0, IMAGE);
		com.mojang.blaze3d.systems.RenderSystem.enableBlend();
		com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
		GuiComponent.blit(
				matrices,
				drawX, drawY,
				drawW, drawH,
				0f, 0f,
				IMG_W, IMG_H,
				IMG_W, IMG_H);
		com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		com.mojang.blaze3d.systems.RenderSystem.disableBlend();
		*///?} else {
		/*// 1.16.5 (Java 8): the 1.17 RenderSystem shader API does not exist yet.
		// Bind the texture via the TextureManager and tint with the legacy
		// fixed-function color4f call, then use the same GuiComponent blit helper.
		mc.getTextureManager().bind(IMAGE);
		com.mojang.blaze3d.systems.RenderSystem.enableBlend();
		com.mojang.blaze3d.systems.RenderSystem.color4f(1f, 1f, 1f, alpha);
		GuiComponent.blit(
				matrices,
				drawX, drawY,
				drawW, drawH,
				0f, 0f,
				IMG_W, IMG_H,
				IMG_W, IMG_H);
		com.mojang.blaze3d.systems.RenderSystem.color4f(1f, 1f, 1f, 1f);
		com.mojang.blaze3d.systems.RenderSystem.disableBlend();
		*///?}
	}

	//? if >=26.1 {
	private static Identifier mkId(String path) {
		return Identifier.fromNamespaceAndPath(BattleMusicClient.MOD_ID, path);
	}
	//?} elif >=1.21 {
	/*private static ResourceLocation mkId(String path) {
		return ResourceLocation.fromNamespaceAndPath(BattleMusicClient.MOD_ID, path);
	}
	*///?} else {
	/*private static ResourceLocation mkId(String path) {
		return new ResourceLocation(BattleMusicClient.MOD_ID, path);
	}
	*///?}

	private float alphaFor(double elapsed) {
		if (elapsed < 0) return 0f;
		if (elapsed < PHASE1_SECONDS) {
			float t = (float) (elapsed / PHASE1_SECONDS);
			return ALPHA_START + (ALPHA_PEAK - ALPHA_START) * t;
		}
		double e2 = elapsed - PHASE1_SECONDS;
		if (e2 < PHASE2_SECONDS) {
			float t = (float) (e2 / PHASE2_SECONDS);
			return ALPHA_PEAK + (ALPHA_END - ALPHA_PEAK) * t;
		}
		return 0f;
	}
}
