package me.lemon553311.battlemusic.state;

import me.lemon553311.battlemusic.config.BattleMusicConfig;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ResumeManager {
	private Path resumeRegularFile;
	private long resumeRegularFrame;
	private Path resumeHeavyFile;
	private long resumeHeavyFrame;
	private long resumeStampNanos = 0L;
	private boolean resumeWasHeavy = false;

	public boolean isActive() {
		return resumeStampNanos != 0L;
	}

	public void remember(boolean wasHeavy, boolean regularLoaded, Path regularFile, long regularFrame,
	                     boolean heavyLoaded, Path heavyFile, long heavyFrame) {
		resumeWasHeavy = wasHeavy;
		if (regularLoaded && regularFile != null) {
			resumeRegularFile = regularFile;
			resumeRegularFrame = regularFrame;
		} else {
			resumeRegularFile = null;
		}
		if (heavyLoaded && heavyFile != null) {
			resumeHeavyFile = heavyFile;
			resumeHeavyFrame = heavyFrame;
		} else {
			resumeHeavyFile = null;
		}
		if (regularLoaded || heavyLoaded) {
			resumeStampNanos = System.nanoTime();
		}
	}

	public boolean canResume(Path file, boolean resumeEnabled, double windowSeconds) {
		if (!resumeEnabled || file == null || resumeStampNanos == 0L) return false;
		double age = (System.nanoTime() - resumeStampNanos) / 1_000_000_000.0;
		return age <= windowSeconds && Files.isReadable(file);
	}

	public boolean inWindow(boolean resumeEnabled, double windowSeconds) {
		if (!resumeEnabled || resumeStampNanos == 0L) return false;
		double age = (System.nanoTime() - resumeStampNanos) / 1_000_000_000.0;
		return age <= windowSeconds;
	}

	public boolean wasHeavy() {
		return resumeWasHeavy;
	}

	public Path regularFile() {
		return resumeRegularFile;
	}

	public long regularFrame() {
		return resumeRegularFrame;
	}

	public Path heavyFile() {
		return resumeHeavyFile;
	}

	public long heavyFrame() {
		return resumeHeavyFrame;
	}

	public void clear() {
		resumeRegularFile = null;
		resumeRegularFrame = 0L;
		resumeHeavyFile = null;
		resumeHeavyFrame = 0L;
		resumeStampNanos = 0L;
		resumeWasHeavy = false;
	}
}
