package me.lemon553311.battlemusic.preview;

import me.lemon553311.battlemusic.BattleMusicClient;
import me.lemon553311.battlemusic.audio.MusicLibrary;
import me.lemon553311.battlemusic.audio.PreviewPlayer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Bridges the mod-menu "Songs" tab preview buttons to the actual audio preview.
 *
 * The preview buttons are clickable text that fire the client command
 * {@code /battlemusic preview <index>}; that command calls
 * {@link #previewByIndex(int)} here. The index maps into a snapshot list the
 * Songs tab publishes via {@link #setEntries(List)} every time it is built.
 *
 * This class deliberately imports NO ModMenu / Cloth Config types, so the client
 * command (registered unconditionally in {@code BattleMusicClient}) can use it
 * even when ModMenu / Cloth Config are not installed.
 */
public final class PreviewRegistry {
	private PreviewRegistry() {}

	private static volatile List<Path> entries = new ArrayList<>();

	/** Publish the ordered list of tracks the Songs tab is currently showing. */
	public static synchronized void setEntries(List<Path> list) {
		entries = (list == null) ? new ArrayList<>() : new ArrayList<>(list);
	}

	/** Play the track at {@code index} at its configured volume + start-at offset. */
	public static synchronized void previewByIndex(int index) {
		if (index < 0 || index >= entries.size()) return;
		Path p = entries.get(index);
		MusicLibrary lib = BattleMusicClient.library();
		float gain = (lib != null) ? lib.effectiveVolumeFor(p) : 1f;
		double startSec = (lib != null) ? lib.startSecondsFor(p) : 0.0;
		PreviewPlayer.play(p, gain, startSec);
	}

	public static void stop() {
		PreviewPlayer.stop();
	}
}
