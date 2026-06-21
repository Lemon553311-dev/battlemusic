package me.lemon553311.battlemusic.lasttotem;

import me.lemon553311.battlemusic.BattleMusicClient;
import me.lemon553311.battlemusic.config.BattleMusicConfig;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * "Last Totem Standing" - a secret, password-gated alert.
 *
 * When the feature is unlocked (see the mod menu) it watches the player's totem
 * count every client tick. The moment that count falls to exactly ONE remaining
 * (e.g. you were holding two - one in the offhand, one in the inventory - and
 * one of them just popped) it:
 *   - plays the bundled alert sound, and
 *   - flashes an image in the centre of the screen (inset 30% from every side),
 *     fading the opacity 20% -> 70% over 3s, then 70% -> 0% over 1s, then gone.
 *
 * Totems are counted across the whole Inventory container, which in vanilla
 * holds the hotbar, main inventory, armor slots AND the offhand slot, so an
 * offhand totem is included in the total.
 *
 * Written for Minecraft 26.1-26.2 mappings + Fabric API: the HUD overlay is
 * registered through {@link HudElementRegistry} (HudRenderCallback was removed),
 * drawing happens through {@link GuiGraphicsExtractor}, the texture id is an
 * {@link Identifier}, and the image is drawn with the scaling/tinted
 * {@code blit} overload using {@link RenderPipelines#GUI_TEXTURED}.
 */
public final class LastTotemFeature {

	/** The password that unlocks the feature from the mod menu. */
	public static final String PASSWORD = "lmao";

	// Bundled at assets/battlemusic/textures/gui/last_totem_standing.png
	private static final Identifier IMAGE =
			Identifier.fromNamespaceAndPath(BattleMusicClient.MOD_ID, "textures/gui/last_totem_standing.png");
	// Native pixel size of that PNG, used for aspect-correct scaling.
	private static final int IMG_W = 1023;
	private static final int IMG_H = 667;

	// Opacity animation (see class doc). "Transparency" in the request is read as
	// opacity here: 0.20 -> 0.70 over PHASE1, then 0.70 -> 0.00 over PHASE2.
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
		ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
		// HudRenderCallback no longer exists in 26.1+. Register a HUD element
		// instead; it draws right before the chat layer (the API handles z-spacing).
		// The element lambda receives a GuiGraphicsExtractor + DeltaTracker.
		HudElementRegistry.attachElementBefore(
				VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath(BattleMusicClient.MOD_ID, "last_totem_standing"),
				(graphics, delta) -> onHudRender(graphics));
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
			// First sample after enabling / joining a world: set the baseline only,
			// so we never fire just for logging in already holding totems.
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
		Inventory inv = player.getInventory();
		int total = 0;
		// The Inventory container spans hotbar + main + armor + offhand in vanilla,
		// so iterating it counts the offhand totem too.
		int size = inv.getContainerSize();
		for (int i = 0; i < size; i++) {
			ItemStack stack = inv.getItem(i);
			if (!stack.isEmpty() && stack.getItem() == Items.TOTEM_OF_UNDYING) {
				total += stack.getCount();
			}
		}
		return total;
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

	private void onHudRender(GuiGraphicsExtractor graphics) {
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

		// 26.1+ blit: (pipeline, texture, x, y, u, v, drawW, drawH,
		//              regionW, regionH, texW, texH, argbColor).
		// Drawing the whole image (region = full texture) scaled into drawW x drawH,
		// tinted white with the animated alpha so it fades in and out.
		graphics.blit(
				RenderPipelines.GUI_TEXTURED,
				IMAGE,
				drawX, drawY,
				0, 0,
				drawW, drawH,
				IMG_W, IMG_H,
				IMG_W, IMG_H,
				color);
	}

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
