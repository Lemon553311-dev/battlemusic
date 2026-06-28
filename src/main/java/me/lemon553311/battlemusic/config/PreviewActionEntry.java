package me.lemon553311.battlemusic.config;

import me.shedaniel.clothconfig2.gui.entries.TextListEntry;
import net.minecraft.network.chat.Component;

/**
 * A clickable Cloth config list entry that runs an arbitrary action when its row
 * is left-clicked.
 *
 * <p>Why this exists: the preview / stop / open-folder controls used to be styled
 * text backed by a RUN_COMMAND / OPEN_FILE ClickEvent. Those route through
 * {@code Screen.handleComponentClicked}, which (a) NPE-crashes when the config
 * screen is opened from the title screen (no player) on 1.20.x and (b) is
 * silently ignored by 1.21.5+ GUI screens. This entry instead runs the action
 * directly from {@link #mouseClicked}, so it works with no player and on every
 * supported version.
 *
 * <p>Cross-version safety: it extends the concrete {@link TextListEntry} (which
 * already implements rendering and every abstract method) and overrides only
 * {@code mouseClicked(double, double, int)} -- a primitive signature -- using the
 * inherited, primitive {@code isMouseOver(double, double)} for hit-testing. It
 * therefore references no per-version GUI graphics class, Button, or widget
 * interface that gets renamed across 1.20.x -> 26.x. The only Minecraft type it
 * touches is {@link Component}.
 */
public class PreviewActionEntry extends TextListEntry {

    private final Runnable onClick;

    public PreviewActionEntry(Component fieldName, Component label, int color, Runnable onClick) {
        super(fieldName, label, color);
        this.onClick = onClick;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Left-click anywhere on this entry's row runs the action. isMouseOver is
        // inherited from Cloth's list Entry and is bounded to this row, so clicks
        // on other rows do not trigger it.
        if (button == 0 && isMouseOver(mouseX, mouseY)) {
            this.onClick.run();
            return true;
        }
        return false;
    }
}
