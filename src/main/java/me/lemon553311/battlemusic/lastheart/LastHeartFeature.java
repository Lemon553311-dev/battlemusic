package me.lemon553311.battlemusic.lastheart;

import me.lemon553311.battlemusic.BattleMusicClient;
import me.lemon553311.battlemusic.config.BattleMusicConfig;
import me.lemon553311.battlemusic.lasttotem.OneShotSound;

//? if fabric && >=1.21.6 {
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
//?} elif fabric {
/*import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
*///?} elif forge && >=1.19 {
/*import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.common.MinecraftForge;
*///?} elif forge {
/*import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
*///?} elif neoforge {
/*import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
*///?}

import net.minecraft.client.Minecraft;
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
*///?} elif >=1.20 {
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
//?} else {
/*import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.resources.ResourceLocation;
*///?} else {
/*import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.resources.ResourceLocation;
*///?}
//? if >=1.21.6 {
import net.minecraft.client.renderer.RenderPipelines;
//?} elif >=1.21.2 {
/*import net.minecraft.client.renderer.RenderType;
*///?}
import net.minecraft.sounds.SoundSource;

/**
 * "Last Heart Standing" - secret password-gated visual, sibling of
 * LastTotemFeature. Flashes an image when a HEAVY battle starts specifically
 * from the low-HP threshold - not for pvp, bosses, or swarms.
 */

public final class LastHeartFeature {

	//? if >=26.1 {
	/*private static final Identifier IMAGE = mkId("textures/gui/last_heart_standing.png");
	*///?} else {
	private static final ResourceLocation IMAGE = mkId("textures/gui/last_heart_standing.png");
	//?}
	private static final int IMG_W = 1023;
	private static final int IMG_H = 667;

	// same sound as the totem alert
	private static final String SOUND = "/assets/battlemusic/lts/LRS_StartSound.ogg";

	// opacity: 0.20 -> 0.70 over phase1, then -> 0 over phase2
	private static final double PHASE1_SECONDS = 3.0;
	private static final double PHASE2_SECONDS = 1.0;
	private static final float ALPHA_START = 0.20f;
	private static final float ALPHA_PEAK = 0.70f;
	private static final float ALPHA_END = 0.00f;

	private static final float EDGE_INSET = 0.30f;

	private final BattleMusicConfig config;

	// written when triggered, read on the render thread
	private volatile boolean animActive = false;
	private volatile long animStartNanos = 0L;

	public LastHeartFeature(BattleMusicConfig config) {
		this.config = config;
	}

	public void init() {
		//? if fabric && >=1.21.6 {
		HudElementRegistry.attachElementBefore(
				VanillaHudElements.CHAT,
				mkId("last_heart_standing"),
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

	// called by the state machine when heavy starts purely from low hp
	public void onHeavyFromLowHp() {
		if (config == null || !config.lastHeartEnabled) return;
		BattleMusicClient.debug("Last Heart Standing: heavy battle from low HP -> flashing image + sound");
		animStartNanos = System.nanoTime();
		animActive = true;
		Minecraft client = Minecraft.getInstance();
		if (client != null) {
			float master = client.options.getSoundSourceVolume(SoundSource.MASTER);
			if (master > 0.0001f) {
				OneShotSound.play(SOUND, master);
			}
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
		if (config == null || !config.lastHeartEnabled) {
			animActive = false;
			return;
		}

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

		int boxX = Math.round(screenW * EDGE_INSET);
		int boxY = Math.round(screenH * EDGE_INSET);
		int boxW = screenW - 2 * boxX;
		int boxH = screenH - 2 * boxY;
		if (boxW <= 0 || boxH <= 0) return;

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
