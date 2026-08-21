package me.lemon553311.battlemusic.audio;

import me.lemon553311.battlemusic.BattleMusicClient;
import me.lemon553311.battlemusic.config.BattleMusicConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * Owns the on-disk music folders and the random track picker.
 * /battlemusic/Regular Battle/ and /battlemusic/Heavy Battle/, .ogg only.
 */

public class MusicLibrary {
	public static final String REGULAR_DIR = "Regular Battle";
	public static final String HEAVY_DIR = "Heavy Battle";

	private final Path root;
	private final List<Path> regular = new ArrayList<>();
	private final List<Path> heavy = new ArrayList<>();

	// last track per category so re-rolls avoid immediate repeats
	private Path lastRegular;
	private Path lastHeavy;
	private Path lastBoth;

	// cached folder mtimes so rescanIfChanged() is cheap
	private long lastRegularMtime = 0L;
	private long lastHeavyMtime = 0L;

	// files stb failed to decode this session (e.g. ogg opus rips). without this,
	// a single broken track gets re-picked and fails every tick, spamming the log.
	// cleared on rescan so a fixed file gets retried.
	private static final Set<String> UNPLAYABLE = ConcurrentHashMap.newKeySet();

	// mark a file undecodable for this session; true only the first time
	public static boolean markUnplayable(Path p) {
		return p != null && UNPLAYABLE.add(p.toAbsolutePath().toString());
	}

	public static boolean isPlayable(Path p) {
		return p != null && !UNPLAYABLE.contains(p.toAbsolutePath().toString());
	}

	private static List<Path> playable(List<Path> list) {
		List<Path> out = new ArrayList<>(list.size());
		for (Path p : list) if (isPlayable(p)) out.add(p);
		return out;
	}

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
				Files.write(readme,
						("Drop .ogg files into 'Regular Battle' and 'Heavy Battle'.\n"
								+ "The song will be picked at random if there's multiple files.\n"
								+ "\n"
								+ "IMPORTANT: files must be Ogg VORBIS. Many .ogg files from YouTube\n"
								+ "rippers / online converters are actually Ogg OPUS and will not play\n"
								+ "(the game log will tell you when that happens). Convert with:\n"
								+ "  ffmpeg -i \"input.ogg\" -c:a libvorbis \"output.ogg\"").getBytes(StandardCharsets.UTF_8));
			}
		} catch (IOException e) {
			BattleMusicClient.LOGGER.warn("Could not create music folders", e);
		}
	}

	// rescan from disk
	public synchronized void rescan() {
		// folder changed: forget decode failures so replaced files get another chance
		UNPLAYABLE.clear();
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
		return !playable(regular).isEmpty();
	}
	public synchronized boolean hasHeavy() {
		return !playable(heavy).isEmpty();
	}
	public synchronized int regularCount() {
		return regular.size();
	}
	public synchronized int heavyCount() {
		return heavy.size();
	}

	// counts excluding files blacklisted this session. the pickers only choose
	// from these, so loop/re-roll decisions must use them too (raw counts made a
	// lone playable track play with restart gaps instead of looping).
	public synchronized int playableRegularCount() {
		return playable(regular).size();
	}
	public synchronized int playableHeavyCount() {
		return playable(heavy).size();
	}

	// snapshots for the mod-menu Songs tab
	public synchronized List<Path> regularTracks() {
		return new ArrayList<>(regular);
	}
	public synchronized List<Path> heavyTracks() {
		return new ArrayList<>(heavy);
	}

	// "<folder>/<filename>", matching the config map keys
	public String keyFor(Path p) {
		if (p == null) return "";
		Path parent = p.getParent();
		String folder = (parent != null && parent.getFileName() != null) ? parent.getFileName().toString() : "";
		return folder + "/" + p.getFileName().toString();
	}

	private boolean isHeavyPath(Path p) {
		Path parent = (p == null) ? null : p.getParent();
		return parent != null && parent.getFileName() != null
				&& HEAVY_DIR.equals(parent.getFileName().toString());
	}

	private BattleMusicConfig.SongSetting settingFor(Path p) {
		BattleMusicConfig cfg = BattleMusicClient.config();
		if (cfg == null || cfg.songSettings == null) return null;
		return cfg.songSettings.get(keyFor(p));
	}

	// seconds into the track where playback starts (per-song setting)
	public double startSecondsFor(Path p) {
		BattleMusicConfig.SongSetting s = settingFor(p);
		return (s != null) ? Math.max(0.0, s.startSeconds) : 0.0;
	}

	// folder volume * per-song volume
	public float effectiveVolumeFor(Path p) {
		BattleMusicConfig cfg = BattleMusicClient.config();
		double folderVol = 1.0;
		if (cfg != null) folderVol = isHeavyPath(p) ? cfg.heavyFolderVolume : cfg.regularFolderVolume;
		BattleMusicConfig.SongSetting s = settingFor(p);
		double songVol = (s != null) ? s.volume : 1.0;
		return (float) Math.max(0.0, folderVol * songVol);
	}

	private double weightOf(Path p) {
		BattleMusicConfig.SongSetting s = settingFor(p);
		double w = (s != null) ? s.weight : 50.0;
		return Math.max(0.0, w);
	}

	// weighted random pick, avoiding an immediate repeat; uniform if all weights are 0
	private Path pickWeighted(List<Path> list, Path avoid) {
		if (list.isEmpty()) return null;
		if (list.size() == 1) return list.get(0);
		double total = 0.0;
		for (Path p : list) total += weightOf(p);
		if (total <= 0.0) return pick(list, avoid); // all excluded -> behave as before
		Path choice = list.get(list.size() - 1);
		int guard = 0;
		do {
			double r = ThreadLocalRandom.current().nextDouble() * total;
			double acc = 0.0;
			for (Path p : list) {
				acc += weightOf(p);
				if (r <= acc) { choice = p; break; }
			}
		} while (choice.equals(avoid) && positiveWeightCount(list) > 1 && guard++ < 8);
		return choice;
	}

	private int positiveWeightCount(List<Path> list) {
		int n = 0;
		for (Path p : list) if (weightOf(p) > 0.0) n++;
		return n;
	}

	public synchronized Path pickRegular() {
		lastRegular = pickWeighted(playable(regular), lastRegular);
		BattleMusicClient.debug("Picked regular track: {}", lastRegular == null ? "<none>" : lastRegular.getFileName());
		return lastRegular;
	}

	public synchronized Path pickHeavy() {
		lastHeavy = pickWeighted(playable(heavy), lastHeavy);
		BattleMusicClient.debug("Picked heavy track: {}", lastHeavy == null ? "<none>" : lastHeavy.getFileName());
		return lastHeavy;
	}

	// union of both folders (pvp trigger with pool=BOTH)
	public synchronized Path pickBoth() {
		List<Path> reg = playable(regular);
		List<Path> hvy = playable(heavy);
		if (reg.isEmpty() && hvy.isEmpty()) return null;
		List<Path> union;
		if (reg.isEmpty()) union = hvy;
		else if (hvy.isEmpty()) union = reg;
		else {
			union = new ArrayList<>(reg.size() + hvy.size());
			union.addAll(reg);
			union.addAll(hvy);
		}
		lastBoth = pickWeighted(union, lastBoth);
		BattleMusicClient.debug("Picked track (PvP both-pool): {}", lastBoth == null ? "<none>" : lastBoth.getFileName());
		return lastBoth;
	}

	// rescan only if a folder mtime changed (folder mtime updates on add/remove,
	// not on in-place file edits - fine for us)
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