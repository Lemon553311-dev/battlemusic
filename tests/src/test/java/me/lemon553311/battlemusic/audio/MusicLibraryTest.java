package me.lemon553311.battlemusic.audio;

import me.lemon553311.battlemusic.BattleMusicClient;
import me.lemon553311.battlemusic.config.BattleMusicConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Track picker, blacklist and folder-scan behavior. Never starts playback,
 * so no audio hardware or decodable files are needed.
 */
class MusicLibraryTest {

	@TempDir
	Path root;

	private MusicLibrary library;

	@BeforeEach
	void setUp() throws Exception {
		BattleMusicClient.setConfig(new BattleMusicConfig());
		library = new MusicLibrary(root);
		library.ensureFolders();
	}

	private Path writeTrack(String folder, String name) throws Exception {
		Path dir = root.resolve(folder);
		Files.createDirectories(dir);
		Path p = dir.resolve(name);
		Files.write(p, ("OggS-fake-" + name).getBytes(StandardCharsets.UTF_8));
		return p;
	}

	private void setWeight(String key, double weight) {
		BattleMusicConfig.SongSetting s = new BattleMusicConfig.SongSetting();
		s.weight = weight;
		BattleMusicClient.config().songSettings.put(key, s);
	}

	@Test
	void emptyFolders_pickNullAndCountsZero() {
		library.rescan();
		assertNull(library.pickRegular());
		assertNull(library.pickHeavy());
		assertNull(library.pickBoth());
		assertEquals(0, library.playableRegularCount());
		assertEquals(0, library.playableHeavyCount());
		assertEquals(0, library.eligibleRegularCount());
		assertEquals(0, library.eligibleHeavyCount());
	}

	@Test
	void singleTrack_alwaysPicked() throws Exception {
		Path track = writeTrack(MusicLibrary.REGULAR_DIR, "only.ogg");
		library.rescan();
		assertEquals(1, library.eligibleRegularCount());
		for (int i = 0; i < 20; i++) {
			assertEquals(track, library.pickRegular());
		}
	}

	@Test
	void weightZeroTrackNeverPicked() throws Exception {
		writeTrack(MusicLibrary.REGULAR_DIR, "never.ogg");
		Path normal = writeTrack(MusicLibrary.REGULAR_DIR, "normal.ogg");
		setWeight("Regular Battle/never.ogg", 0.0D);
		library.rescan();
		assertEquals(1, library.eligibleRegularCount());
		assertEquals(2, library.playableRegularCount());
		for (int i = 0; i < 200; i++) {
			assertEquals(normal, library.pickRegular());
		}
	}

	@Test
	void allWeightZero_pickNull() throws Exception {
		writeTrack(MusicLibrary.REGULAR_DIR, "a.ogg");
		writeTrack(MusicLibrary.REGULAR_DIR, "b.ogg");
		setWeight("Regular Battle/a.ogg", 0.0D);
		setWeight("Regular Battle/b.ogg", 0.0D);
		library.rescan();
		assertEquals(0, library.eligibleRegularCount());
		assertNull(library.pickRegular());
	}

	@Test
	void twoTracks_bothPickedOverRuns() throws Exception {
		writeTrack(MusicLibrary.REGULAR_DIR, "a.ogg");
		writeTrack(MusicLibrary.REGULAR_DIR, "b.ogg");
		library.rescan();
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 500; i++) {
			seen.add(library.pickRegular().getFileName().toString());
		}
		assertTrue(seen.contains("a.ogg"));
		assertTrue(seen.contains("b.ogg"));
	}

	@Test
	void distributionRoughlyFollowsWeights() throws Exception {
		writeTrack(MusicLibrary.HEAVY_DIR, "rare.ogg");
		writeTrack(MusicLibrary.HEAVY_DIR, "common.ogg");
		setWeight("Heavy Battle/rare.ogg", 25.0D);
		setWeight("Heavy Battle/common.ogg", 75.0D);
		library.rescan();
		int rare = 0;
		int runs = 10000;
		for (int i = 0; i < runs; i++) {
			if (library.pickHeavy().getFileName().toString().equals("rare.ogg")) {
				rare++;
			}
		}
		double share = rare / (double) runs;
		assertTrue(share > 0.15 && share < 0.35, "rare share was " + share);
	}

	@Test
	void unplayableExcludedFromPickAndCounts() throws Exception {
		Path track = writeTrack(MusicLibrary.REGULAR_DIR, "broken.ogg");
		library.rescan();
		assertNotNull(library.pickRegular());
		MusicLibrary.markUnplayable(track);
		assertNull(library.pickRegular());
		assertEquals(0, library.playableRegularCount());
		assertEquals(0, library.eligibleRegularCount());
	}

	@Test
	void rescanClearsBlacklist() throws Exception {
		Path track = writeTrack(MusicLibrary.REGULAR_DIR, "fixed.ogg");
		library.rescan();
		MusicLibrary.markUnplayable(track);
		assertNull(library.pickRegular());
		library.rescan();
		assertEquals(track, library.pickRegular());
	}

	@Test
	void rescanIfChanged_detectsFolderChange() throws Exception {
		writeTrack(MusicLibrary.REGULAR_DIR, "a.ogg");
		library.rescan();
		assertFalse(library.rescanIfChanged());
		Path dir = root.resolve(MusicLibrary.REGULAR_DIR);
		writeTrack(MusicLibrary.REGULAR_DIR, "b.ogg");
		// force a newer folder mtime so the check cannot flake on coarse clocks
		Files.setLastModifiedTime(dir, java.nio.file.attribute.FileTime.fromMillis(
				System.currentTimeMillis() + 5000L));
		assertTrue(library.rescanIfChanged());
		assertEquals(2, library.regularCount());
		assertFalse(library.rescanIfChanged());
	}

	@Test
	void listOgg_caseInsensitiveAndIgnoresOtherFiles() throws Exception {
		writeTrack(MusicLibrary.REGULAR_DIR, "upper.OGG");
		writeTrack(MusicLibrary.REGULAR_DIR, "song.mp3");
		writeTrack(MusicLibrary.REGULAR_DIR, "notes.txt");
		Path sub = root.resolve(MusicLibrary.REGULAR_DIR).resolve("nested");
		Files.createDirectories(sub);
		Files.write(sub.resolve("deep.ogg"), new byte[]{1});
		library.rescan();
		List<Path> tracks = library.regularTracks();
		assertEquals(1, tracks.size());
		assertEquals("upper.OGG", tracks.get(0).getFileName().toString());
	}

	@Test
	void keyFor_usesFolderAndFileName() throws Exception {
		Path track = writeTrack(MusicLibrary.HEAVY_DIR, "boss.ogg");
		assertEquals("Heavy Battle/boss.ogg", library.keyFor(track));
	}
}
