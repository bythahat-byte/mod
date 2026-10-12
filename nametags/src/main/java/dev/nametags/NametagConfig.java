package dev.nametags;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Settings stored in {@code config/nametags.json}. */
public final class NametagConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Show your own nametag in third person (F5). */
	public boolean showOwn = true;
	/** Draw nametag text with a drop shadow. */
	public boolean textShadow = true;
	/** Background darkness 0-100, or -1 to use Minecraft's own setting. */
	public int backgroundOpacity = -1;
	/** Nametag size multiplier (1.0 = vanilla). */
	public float scale = 1.0f;

	void clamp() {
		backgroundOpacity = backgroundOpacity < 0 ? -1 : Math.min(backgroundOpacity, 100);
		scale = Math.max(0.25f, Math.min(scale, 4.0f));
	}

	static NametagConfig load(Path file) {
		NametagConfig config = null;
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file)) {
				config = GSON.fromJson(reader, NametagConfig.class);
			} catch (Exception e) {
				Nametags.LOGGER.warn("Could not read {}, using defaults", file, e);
			}
		}
		if (config == null) {
			config = new NametagConfig();
		}
		config.clamp();
		config.save(file);
		return config;
	}

	void save(Path file) {
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file)) {
				GSON.toJson(this, writer);
			}
		} catch (Exception e) {
			Nametags.LOGGER.warn("Could not write {}", file, e);
		}
	}
}
