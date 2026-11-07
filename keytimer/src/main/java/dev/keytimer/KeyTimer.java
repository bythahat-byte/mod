package dev.keytimer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Presses a list of keys one after another with a wait after each one, e.g. "1", wait 60 s,
 * "2", wait 60 s, ... The press goes through Minecraft's key bindings, exactly as if you had
 * pressed the key, so it does whatever that key is bound to (hotbar slot, attack, drop...).
 */
public final class KeyTimer implements ClientModInitializer {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** One step: press {@code key}, then wait {@code waitSeconds} before the next step. */
	public static final class Step {
		String key;
		double waitSeconds;

		Step(String key, double waitSeconds) {
			this.key = key;
			this.waitSeconds = waitSeconds;
		}
	}

	public static final class Config {
		List<Step> steps = new ArrayList<>();
		/** Start over from the first step after the last one. */
		boolean loop = true;
	}

	private static Config config = new Config();
	private static Path configFile;
	private static KeyBinding startStopKey;

	private static boolean running;
	private static int stepIndex;
	private static long nextStepAt;
	/** Key held down this tick, released on the next one. */
	private static InputUtil.Key heldKey;

	@Override
	public void onInitializeClient() {
		configFile = FabricLoader.getInstance().getConfigDir().resolve("keytimer.json");
		load();

		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("keytimer", "main"));
		startStopKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.keytimer.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));

		ClientTickEvents.END_CLIENT_TICK.register(KeyTimer::tick);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(literal("keytimer")
				.executes(ctx -> list(ctx.getSource()))
				.then(literal("add").then(argument("key", StringArgumentType.word())
						.then(argument("waitSeconds", DoubleArgumentType.doubleArg(0, 86400)).executes(ctx -> {
							String key = StringArgumentType.getString(ctx, "key");
							if (parseKey(key) == null) {
								ctx.getSource().sendError(Text.literal("Unknown key \"" + key
										+ "\". Use things like 1-9, a-z, space, left (mouse), right (mouse), f, q, e."));
								return 0;
							}
							config.steps.add(new Step(key.toLowerCase(Locale.ROOT), DoubleArgumentType.getDouble(ctx, "waitSeconds")));
							save();
							return list(ctx.getSource());
						}))))
				.then(literal("remove").then(argument("step", IntegerArgumentType.integer(1)).executes(ctx -> {
					int i = IntegerArgumentType.getInteger(ctx, "step") - 1;
					if (i >= config.steps.size()) {
						ctx.getSource().sendError(Text.literal("There is no step " + (i + 1)));
						return 0;
					}
					stop(null);
					config.steps.remove(i);
					save();
					return list(ctx.getSource());
				})))
				.then(literal("clear").executes(ctx -> {
					stop(null);
					config.steps.clear();
					save();
					return list(ctx.getSource());
				}))
				.then(literal("loop").then(argument("on", BoolArgumentType.bool()).executes(ctx -> {
					config.loop = BoolArgumentType.getBool(ctx, "on");
					save();
					return list(ctx.getSource());
				})))
				.then(literal("start").executes(ctx -> {
					start(ctx.getSource()::sendFeedback);
					return 1;
				}))
				.then(literal("stop").executes(ctx -> {
					stop(ctx.getSource()::sendFeedback);
					return 1;
				}))));
	}

	// ---------------------------------------------------------------- running

	private static void tick(MinecraftClient client) {
		while (startStopKey.wasPressed()) {
			if (running) {
				stop(KeyTimer::actionBar);
			} else {
				start(KeyTimer::actionBar);
			}
		}
		if (heldKey != null) {
			KeyBinding.setKeyPressed(heldKey, false);
			heldKey = null;
		}
		if (!running) {
			return;
		}
		if (client.player == null) {
			stop(null); // left the world
			return;
		}
		if (System.nanoTime() < nextStepAt) {
			return;
		}
		if (stepIndex >= config.steps.size()) {
			if (!config.loop) {
				stop(KeyTimer::actionBar);
				return;
			}
			stepIndex = 0;
		}
		Step step = config.steps.get(stepIndex);
		InputUtil.Key key = parseKey(step.key);
		if (key != null) {
			// same as a real key press: counts as "pressed" once and held for one tick
			KeyBinding.setKeyPressed(key, true);
			KeyBinding.onKeyPressed(key);
			heldKey = key;
		}
		nextStepAt = System.nanoTime() + (long) (step.waitSeconds * 1_000_000_000L);
		stepIndex++;
	}

	private static void start(java.util.function.Consumer<Text> feedback) {
		if (config.steps.isEmpty()) {
			feedback.accept(Text.literal("Key Timer has no steps. Add one with /keytimer add <key> <seconds>").formatted(Formatting.RED));
			return;
		}
		running = true;
		stepIndex = 0;
		nextStepAt = 0; // first key right away
		feedback.accept(Text.literal("Key Timer started (" + config.steps.size() + " steps"
				+ (config.loop ? ", looping" : "") + ")").formatted(Formatting.GREEN));
	}

	private static void stop(java.util.function.Consumer<Text> feedback) {
		boolean was = running;
		running = false;
		if (heldKey != null) {
			KeyBinding.setKeyPressed(heldKey, false);
			heldKey = null;
		}
		if (was && feedback != null) {
			feedback.accept(Text.literal("Key Timer stopped").formatted(Formatting.RED));
		}
	}

	private static void actionBar(Text text) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player != null) {
			client.player.sendMessage(text, true);
		}
	}

	/** "1", "a", "space", "left"/"right"/"middle" (mouse) or any Minecraft key name like "key.keyboard.f6". */
	private static InputUtil.Key parseKey(String name) {
		String n = name.toLowerCase(Locale.ROOT);
		String translationKey;
		if (n.startsWith("key.")) {
			translationKey = n;
		} else if (n.equals("left") || n.equals("right") || n.equals("middle")) {
			translationKey = "key.mouse." + n;
		} else {
			translationKey = "key.keyboard." + n;
		}
		try {
			InputUtil.Key key = InputUtil.fromTranslationKey(translationKey);
			return key == null || key.equals(InputUtil.UNKNOWN_KEY) ? null : key;
		} catch (RuntimeException e) {
			return null;
		}
	}

	// ---------------------------------------------------------------- config

	private static int list(FabricClientCommandSource source) {
		source.sendFeedback(Text.literal("Key Timer" + (running ? " (running)" : "") + ":").formatted(Formatting.GOLD));
		if (config.steps.isEmpty()) {
			source.sendFeedback(Text.literal("  no steps yet: /keytimer add 1 60").formatted(Formatting.GRAY));
		}
		for (int i = 0; i < config.steps.size(); i++) {
			Step s = config.steps.get(i);
			source.sendFeedback(Text.literal(String.format(Locale.ROOT, "  %d. press %s, then wait %ss",
					i + 1, s.key, trim(s.waitSeconds))).formatted(Formatting.WHITE));
		}
		source.sendFeedback(Text.literal("  loop: " + (config.loop ? "on" : "off")
				+ "   /keytimer start | stop | add | remove <n> | clear | loop true|false").formatted(Formatting.DARK_GRAY));
		return 1;
	}

	private static String trim(double d) {
		return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
	}

	private static void load() {
		if (Files.exists(configFile)) {
			try (Reader reader = Files.newBufferedReader(configFile)) {
				Config loaded = GSON.fromJson(reader, Config.class);
				if (loaded != null) {
					config = loaded;
				}
			} catch (Exception ignored) {
			}
		}
		if (config.steps == null) {
			config.steps = new ArrayList<>();
		}
	}

	private static void save() {
		try {
			Files.createDirectories(configFile.getParent());
			try (Writer writer = Files.newBufferedWriter(configFile)) {
				GSON.toJson(config, writer);
			}
		} catch (Exception ignored) {
		}
	}
}
