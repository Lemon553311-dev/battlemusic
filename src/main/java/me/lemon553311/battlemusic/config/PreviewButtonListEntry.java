package me.lemon553311.battlemusic.config;

import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;

//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}

import java.util.List;
import java.util.Optional;

/**
 * A Cloth Config list entry that is just a clickable {@link Button}, used for the
 * Songs-tab "Preview" / "Stop preview" / "Open folder" actions.
 *
 * <p>Why this exists: Cloth Config ships no built-in button entry, so the old
 * implementation faked one with clickable text whose {@code Style} carried a
 * {@code RUN_COMMAND}/{@code OPEN_FILE} {@code ClickEvent}. That routes through
 * {@code Screen.handleComponentClicked}, which:
 * <ul>
 *   <li>needs a logged-in player and therefore NPE-crashes when the config
 *       screen is opened from the main menu (no player), and</li>
 *   <li>is simply ignored for {@code RUN_COMMAND}/{@code OPEN_FILE} in 1.21.5+
 *       GUI screens ("Don't know how to handle RunCommand[...]").</li>
 * </ul>
 * A real button runs our code directly on click \u2014 no player, no command
 * dispatch \u2014 so it behaves identically on the title screen and in-game on
 * every supported version.
 *
 * <p>There is nothing to save, so the value type is an unused sentinel and the
 * entry never reports itself as edited.
 *
 * <p>Multi-version note (Stonecutter {@code //?} directive below): Cloth's
 * {@code render(\u2026)} first parameter is {@code GuiGraphicsExtractor} on 26.1+
 * and {@code GuiGraphics} before, mirroring the HUD draw code in
 * {@code LastHeartFeature}. VERIFY on build that {@code Button},
 * {@code GuiEventListener} and {@code NarratableEntry} keep these names on 26.x.
 */
public class PreviewButtonListEntry extends AbstractConfigListEntry<Object> {

	private static final Object NO_VALUE = new Object();
	private static final int BUTTON_WIDTH = 160;
	private static final int BUTTON_HEIGHT = 20;

	private final Button button;

	public PreviewButtonListEntry(Component fieldName, Component label, Runnable onClick) {
		// Cloth's AbstractConfigListEntry takes (fieldName, requiresRestart); this
		// entry never needs a restart.
		super(fieldName, false);
		this.button = Button.builder(label, b -> onClick.run())
				.width(BUTTON_WIDTH)
				.build();
	}

	@Override
	public Object getValue() {
		return NO_VALUE;
	}

	@Override
	public Optional<Object> getDefaultValue() {
		return Optional.of(NO_VALUE);
	}

	@Override
	public void save() {
		// Nothing to persist: this entry is a pure action button.
	}

	@Override
	//? if >=26.1 {
	public void render(GuiGraphicsExtractor graphics, int index, int y, int x, int entryWidth, int entryHeight,
			int mouseX, int mouseY, boolean isHovered, float delta) {
	//?} else {
	/*public void render(GuiGraphics graphics, int index, int y, int x, int entryWidth, int entryHeight,
			int mouseX, int mouseY, boolean isHovered, float delta) {
	*///?}
		// Right-align the button within the row, vertically centred.
		int w = Math.min(BUTTON_WIDTH, entryWidth);
		this.button.setWidth(w);
		this.button.setX(x + entryWidth - w);
		this.button.setY(y + (entryHeight - BUTTON_HEIGHT) / 2);
		this.button.render(graphics, mouseX, mouseY, delta);
	}

	@Override
	public List<? extends GuiEventListener> children() {
		return List.of(this.button);
	}

	@Override
	public List<? extends NarratableEntry> narratables() {
		return List.of(this.button);
	}
}
