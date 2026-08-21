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
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} elif >=1.20 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
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
/*import net.minecraft.resources.Identifier;
*///?} else {
import net.minecraft.resources.ResourceLocation;
//?}
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * "Last Totem Standing" - secret password-gated alert: watches the totem count
 * every tick and fires a sound + image flash when it drops to exactly one.
 */

public final class LastTotemFeature {

	// good job you found the password to this not so hidden feature. Have fun!
	// Input it in the "Advanced" tab in the modmenu (or just enable it in the config lmao)
	public static final String PASSWORD = "lmao";

	//? if >=26.1 {
	/*private static final Identifier IMAGE = mkId("textures/gui/last_totem_standing.png");
	*///?} else {
	private static final ResourceLocation IMAGE = mkId("textures/gui/last_totem_standing.png");
	//?}
	private static final int IMG_W = 1023;
	private static final int IMG_H = 667;

	// opacity: 0.20 -> 0.70 over phase1, then -> 0 over phase2
	private static final double PHASE1_SECONDS = 3.0;
	private static final double PHASE2_SECONDS = 1.0;
	private static final float ALPHA_START = 0.20f;
	private static final float ALPHA_PEAK = 0.70f;
	private static final float ALPHA_END = 0.00f;

	// inset from every screen edge; image is centred in the remaining box
	private static final float EDGE_INSET = 0.30f;

	private final BattleMusicConfig config;

	// last sampled total totem count (-1 = not sampled yet)
	private int lastTotemCount = -1;

	// written on the client tick, read on the render thread
	private volatile boolean animActive = false;
	private volatile long animStartNanos = 0L;

	public LastTotemFeature(BattleMusicConfig config) {
		this.config = config;
	}

	public void init() {
		// tick source per loader; counting logic is shared
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

		// HUD hook per loader/version
		//? if fabric && >=1.21.6 {
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
			// first sample: baseline only
			lastTotemCount = count;
			return;
		}

		// falling edge to exactly one remaining
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
		int size = inv.getContainerSize();
		for (int i = 0; i < size; i++) {
			if (isTotem(inv.getItem(i))) {
				total += inv.getItem(i).getCount();
			}
		}
		// a totem held on the cursor lives on the open menu, not the inventory;
		// count it too to avoid a false dip to 1
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
		animStartNanos = System.nanoTime();
		animActive = true;
		float master = client.options.getSoundSourceVolume(SoundSource.MASTER);
		if (master > 0.0001f) {
			OneShotSound.play(master);
		}
	}

	//? if >=26.1 {
	/*private void onHudRender(GuiGraphicsExtractor graphics) {
	*///?} elif >=1.20 {
	private void onHudRender(GuiGraphics graphics) {
	//?} else {
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

		// 30% inset from each side
		int boxX = Math.round(screenW * EDGE_INSET);
		int boxY = Math.round(screenH * EDGE_INSET);
		int boxW = screenW - 2 * boxX;
		int boxH = screenH - 2 * boxY;
		if (boxW <= 0 || boxH <= 0) return;

		// fit inside the box, aspect preserved
		float scale = Math.min(boxW / (float) IMG_W, boxH / (float) IMG_H);
		int drawW = Math.max(1, Math.round(IMG_W * scale));
		int drawH = Math.max(1, Math.round(IMG_H * scale));
		int drawX = boxX + (boxW - drawW) / 2;
		int drawY = boxY + (boxH - drawH) / 2;

		int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
		int color = (a << 24) | 0x00FFFFFF; // white tint, animated alpha

		//? if >=1.21.6 {
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
		/*// 1.21.2-1.21.5: same shape, first arg is RenderType::guiTextured
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
		/*// legacy scaled blit, tint via shader color
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
		/*// pre-GuiGraphics: PoseStack + GuiComponent.blit, shader-color tint
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
		/*// 1.16.5: no 1.17 shader API; TextureManager bind + fixed-function color4f
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
	/*private static Identifier mkId(String path) {
		return Identifier.fromNamespaceAndPath(BattleMusicClient.MOD_ID, path);
	}
	*///?} elif >=1.21 {
	private static ResourceLocation mkId(String path) {
		return ResourceLocation.fromNamespaceAndPath(BattleMusicClient.MOD_ID, path);
	}
	//?} else {
	/*// 2-arg constructor is deprecated-for-removal on newer MC but is the only
	// option pre-1.21
	@SuppressWarnings({"deprecation", "removal"})
	private static ResourceLocation mkId(String path) {
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
