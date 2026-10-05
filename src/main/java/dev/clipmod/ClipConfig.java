package dev.clipmod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Settings stored in {@code config/clipmod.json}. */
public final class ClipConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Whether the replay buffer is recording in the background. */
	public boolean enabled = true;
	/** How many seconds are kept in memory (and the default clip length). */
	public int bufferSeconds = 30;
	/** Frames per second captured into the buffer. */
	public int fps = 30;
	/** Clips are scaled down to this height in pixels (0 = native resolution). */
	public int maxHeight = 720;
	/** JPEG quality of each buffered frame, 0.1 - 1.0. */
	public float quality = 0.85f;
	/** Convert clips to .mp4 with ffmpeg when it is available (otherwise .avi is kept). */
	public boolean convertToMp4 = true;
	/** Path to the ffmpeg executable; empty = look for "ffmpeg" on the PATH. */
	public String ffmpegPath = "";
	/** Folder clips are written to; empty = ".minecraft/clips". */
	public String outputFolder = "";

	public void clamp() {
		bufferSeconds = Math.max(1, Math.min(bufferSeconds, 600));
		fps = Math.max(1, Math.min(fps, 60));
		maxHeight = Math.max(0, maxHeight);
		quality = Math.max(0.1f, Math.min(quality, 1.0f));
		if (ffmpegPath == null) {
			ffmpegPath = "";
		}
		if (outputFolder == null) {
			outputFolder = "";
		}
	}

	public static ClipConfig load(Path file) {
		ClipConfig config = null;
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file)) {
				config = GSON.fromJson(reader, ClipConfig.class);
			} catch (Exception e) {
				ClipMod.LOGGER.warn("Could not read {}, using defaults", file, e);
			}
		}
		if (config == null) {
			config = new ClipConfig();
		}
		config.clamp();
		config.save(file);
		return config;
	}

	public void save(Path file) {
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			ClipMod.LOGGER.warn("Could not write {}", file, e);
		}
	}
}
