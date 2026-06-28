package me.lemon553311.battlemusic.lastheart;

import me.lemon553311.battlemusic.BattleMusicClient;
import me.lemon553311.battlemusic.config.BattleMusicConfig;
import me.lemon553311.battlemusic.lasttotem.OneShotSound;

//? if >=1.21.6 {
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
//?} else {
/*import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
*///?}

import net.minecraft.client.Minecraft;
//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
*///?}
//? if >=1.21.5 {
import net.minecraft.client.renderer.RenderPipelines;
//?}
import net.minecraft.sounds.SoundSource;

/**
 * "Last Heart Standing" - a secret, password-gated visual (sibling of
 * {@link me.lemon553311.battlemusic.lasttotem.LastTotemFeature}).
 *
 * It flashes an image in the centre of the screen the moment a HEAVY battle
 * starts SPECIFICALLY because the player's health dropped to/under the heavy HP
 * threshold (the low-HP escalation). By design it does NOT fire for PvP-driven
 * heavy, bosses, or big swarms - only the low-HP trigger, as requested.
 *
 * Multi-version notes (Stonecutter //? directives below):
 *   - HUD registration: HudElementRegistry on 1.21.6+, HudRenderCallback before.
 *   - Class names: Identifier/GuiGraphicsExtractor on 26.1+, ResourceLocation/
 *     GuiGraphics before (official Mojang mappings renamed these at the non-obf
 *     switch).
 *   - Draw call: RenderPipelines blit overload on 1.21.5+, the legacy scaled
 *     GuiGraphics blit + RenderSystem tint before.
 */
public final class LastHeartFeature {

	// Bundled at assets/battlemusic/textures/gui/last_heart_standing.png
	//? if >=26.1 {
	private static final Identifier IMAGE = mkId("textures/gui/last_heart_standing.png");
	//?} else {
	/*private static final ResourceLocation IMAGE = mkId("textures/gui/last_heart_standing.png");
	*///?}
	// Native pixel size of that PNG, used for aspect-correct scaling.
	private static final int IMG_W = 1023;
	private static final int IMG_H = 667;

	// use the same sound as the totem alert
	private static final String SOUND = "/assets/battlemusic/lts/LRS_StartSound.ogg";

	// Opacity animation: 0.20 -> 0.70 over PHASE1, then 0.70 -> 0.00 over PHASE2.
	private static final double PHASE1_SECONDS = 3.0;
	private static final double PHASE2_SECONDS = 1.0;
	private static final float ALPHA_START = 0.20f;
	private static final float ALPHA_PEAK = 0.70f;
	private static final float ALPHA_END = 0.00f;

	// Inset from every screen edge -> the image is centred in the remaining box.
	private static final float EDGE_INSET = 0.30f;

	private final BattleMusicConfig config;

	// Animation state: written when triggered, read on the render thread.
	private volatile boolean animActive = false;
	private volatile long animStartNanos = 0L;

	public LastHeartFeature(BattleMusicConfig config) {
		this.config = config;
	}

	public void init() {
		//? if >=1.21.6 {
		// HudRenderCallback no longer exists in 1.21.6+. Register a HUD element
		// that draws right before the chat layer, exactly like the totem overlay.
		HudElementRegistry.attachElementBefore(
				VanillaHudElements.CHAT,
				mkId("last_heart_standing"),
				(graphics, delta) -> onHudRender(graphics));
		//?} else {
		/*HudRenderCallback.EVENT.register((graphics, tickDelta) -> onHudRender(graphics));
		*///?}
	}

	/**
	 * Called by the state machine when a heavy battle starts purely from the
	 * low-HP threshold (no PvP / boss / swarm). No-op unless the feature is on.
	 */
	public void onHeavyFromLowHp() {
		if (config == null || !config.lastHeartEnabled) return;
		BattleMusicClient.debug("Last Heart Standing: heavy battle from low HP -> flashing image + sound");
		// (Re)start the overlay animation from the top.
		animStartNanos = System.nanoTime();
		animActive = true;
		// Play the alert at the player's master volume, on the mod's own audio path
		// (same fire-and-forget Ogg player the totem alert uses).
		Minecraft client = Minecraft.getInstance();
		if (client != null) {
			float master = client.options.getSoundSourceVolume(SoundSource.MASTER);
			if (master > 0.0001f) {
				OneShotSound.play(SOUND, master);
			}
		}
	}

	//? if >=26.1 {
	private void onHudRender(GuiGraphicsExtractor graphics) {
	//?} else {
	/*private void onHudRender(GuiGraphics graphics) {
	*///?}
		if (!animActive) return;
		// If the feature was switched off mid-animation, stop drawing immediately.
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

		//? if >=1.21.5 {
		// 1.21.5+ blit: (pipeline, texture, x, y, u, v, drawW, drawH,
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
		//?} else {
		/*// Legacy (<1.21.5) scaled blit: tint via RenderSystem shader color.
		// VERIFY on build - the GuiGraphics.blit overload shape shifted a few
		// times across 1.20.1 / 1.21.0-1.21.4; see PORTING.md for the exact
		// signatures per version if this does not resolve.
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
