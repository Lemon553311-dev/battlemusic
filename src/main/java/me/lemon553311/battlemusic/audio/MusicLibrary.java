package me.lemon553311.battlemusic.audio;

import me.lemon553311.battlemusic.BattleMusicClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * @author Dragon6555
 * Owns the on-disk music folders and the random track picker.
 *
 * Layout (created automatically on first launch):
 *   /battlemusic/Regular Battle/
 *   /battlemusic/Heavy Battle/
 *
 * Only Ogg Vorbis (.ogg) is supported.
 * STB Vorbis decoder reads without any dependency.
 * See the README for a one-line ffmpeg command to convert MP3/WAV to OGG.
 */

public class MusicLibrary {
	public static final String REGULAR_DIR = "Regular Battle";
	public static final String HEAVY_DIR = "Heavy Battle";

	private final Path root;
	private final List<Path> regular = new ArrayList<>();
	private final List<Path> heavy = new ArrayList<>();

	// Remember the last track per category so re-rolls avoid immediate repeats.
	private Path lastRegular;
	private Path lastHeavy;
	private Path lastBoth;

	// Cached folder modification times so we can rescan only when a folder
	// actually changed (avoids disk I/O on the render thread when nothing did).
	private long lastRegularMtime = 0L;
	private long lastHeavyMtime = 0L;

	public MusicLibrary(Path root) {
		this.root = root;
	}

	public Path getRootFolder() {
		return root;
	}

	public void ensureFolders() {
		try {
			Files.createDirectories(root.resolve(REGULAR_DIR));
			Files.createDirectories(root.resolve(HEAVY_DIR));
			Path readme = root.resolve("PUT_YOUR_MUSIC_HERE.txt");
			if (!Files.exists(readme)) {
				Files.writeString(readme,
						"Drop .ogg files into 'Regular Battle' and 'Heavy Battle'.\n"
								+ "The song will be picked at random if there's multiple files.");
			}
		} catch (IOException e) {
			BattleMusicClient.LOGGER.warn("Could not create music folders", e);
		}
	}

	// Re-read both folders from disk. Updates the cached mtimes so a follow-up rescanIfChanged() is a no-op until the folders change again.
	public synchronized void rescan() {
		regular.clear();
		heavy.clear();
		regular.addAll(listOgg(root.resolve(REGULAR_DIR)));
		heavy.addAll(listOgg(root.resolve(HEAVY_DIR)));
		lastRegularMtime = folderMtime(root.resolve(REGULAR_DIR));
		lastHeavyMtime = folderMtime(root.resolve(HEAVY_DIR));
		BattleMusicClient.debug("Library rescan: {} regular, {} heavy track(s) under {}", regular.size(), heavy.size(), root);

		if (BattleMusicClient.config() != null && BattleMusicClient.config().debug) {
			for (Path p : regular) BattleMusicClient.debug("  regular: {}", p.getFileName());
			for (Path p : heavy) BattleMusicClient.debug("  heavy:   {}", p.getFileName());
		}
	}

	private static List<Path> listOgg(Path dir) {
		List<Path> out = new ArrayList<>();

		if (!Files.isDirectory(dir)) return out;

		try (Stream<Path> stream = Files.list(dir)) {
			stream.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ogg")).sorted().forEach(out::add);
		} catch (IOException e) {
			BattleMusicClient.LOGGER.warn("Could not list {}", dir, e);
		}
		return out;
	}

	public synchronized boolean hasRegular() {
		return !regular.isEmpty();
	}
	public synchronized boolean hasHeavy() {
		return !heavy.isEmpty();
	}
	public synchronized int regularCount() {
		return regular.size();
	}
	public synchronized int heavyCount() {
		return heavy.size();
	}

	public synchronized Path pickRegular() {
		lastRegular = pick(regular, lastRegular);
		BattleMusicClient.debug("Picked regular track: {}", lastRegular == null ? "<none>" : lastRegular.getFileName());
		return lastRegular;
	}

	public synchronized Path pickHeavy() {
		lastHeavy = pick(heavy, lastHeavy);
		BattleMusicClient.debug("Picked heavy track: {}", lastHeavy == null ? "<none>" : lastHeavy.getFileName());
		return lastHeavy;
	}

	// Picks a track from the union of both folders (used by the PvP trigger when its pool is BOTH).
	public synchronized Path pickBoth() {
		if (regular.isEmpty() && heavy.isEmpty()) return null;
		List<Path> union;
		if (regular.isEmpty()) union = heavy;
		else if (heavy.isEmpty()) union = regular;
		else {
			union = new ArrayList<>(regular.size() + heavy.size());
			union.addAll(regular);
			union.addAll(heavy);
		}
		lastBoth = pick(union, lastBoth);
		BattleMusicClient.debug("Picked track (PvP both-pool): {}", lastBoth == null ? "<none>" : lastBoth.getFileName());
		return lastBoth;
	}

	/**
	 * Rescan only if a music folder's modification time changed since the last
	 * scan. Folder mtime updates when entries are added or removed (file content
	 * edits do NOT touch it — fine for us). Cheap stat keeps the "drop new files
	 * in mid-session" UX without paying disk I/O on every battle start.
	 */
    public synchronized boolean rescanIfChanged() {
		long regMtime = folderMtime(root.resolve(REGULAR_DIR));
		long hvyMtime = folderMtime(root.resolve(HEAVY_DIR));
		if (regMtime == lastRegularMtime && hvyMtime == lastHeavyMtime) return false;
		rescan();
		return true;
	}

	private static long folderMtime(Path dir) {
		try {
			if (!Files.isDirectory(dir)) return 0L;
			return Files.getLastModifiedTime(dir).toMillis();
		} catch (IOException e) {
			return 0L;
		}
	}

	private static Path pick(List<Path> list, Path avoid) {
		if (list.isEmpty()) return null;
		if (list.size() == 1) return list.get(0);
		Path choice;
		int guard = 0;

		do {
			choice = list.get(ThreadLocalRandom.current().nextInt(list.size()));
		} while (choice.equals(avoid) && guard++ < 8);
		return choice;
	}
}