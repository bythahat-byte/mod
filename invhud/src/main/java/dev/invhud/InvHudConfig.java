package dev.invhud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Settings stored in {@code config/invhud.json}. */
public final class InvHudConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public enum Corner {
		TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT;

		public static Corner parse(String s) {
			return valueOf(s.toUpperCase(Locale.ROOT).replace('-', '_'));
		}

		public String pretty() {
			return name().toLowerCase(Locale.ROOT).replace('_', '-');
		}
	}

	public boolean enabled = true;
	public Corner corner = Corner.TOP_LEFT;
	/** Size multiplier (1.0 = same size as the hotbar). */
	public float scale = 0.75f;
	/** Background darkness 0-100. */
	public int background = 40;
	/** Also show armor and offhand next to the inventory. */
	public boolean armor = true;
	/** Hide the HUD while any screen (inventory, chest, menu...) is open. */
	public boolean hideInMenus = true;

	void clamp() {
		if (corner == null) {
			corner = Corner.TOP_LEFT;
		}
		scale = Math.max(0.25f, Math.min(scale, 3.0f));
		background = Math.max(0, Math.min(background, 100));
	}

	static InvHudConfig load(Path file) {
		InvHudConfig config = null;
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file)) {
				config = GSON.fromJson(reader, InvHudConfig.class);
			} catch (Exception e) {
				InvHud.LOGGER.warn("Could not read {}, using defaults", file, e);
			}
		}
		if (config == null) {
			config = new InvHudConfig();
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
			InvHud.LOGGER.warn("Could not write {}", file, e);
		}
	}
}
