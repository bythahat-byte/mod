package dev.clipmod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.clipmod.capture.EncodedFrame;
import dev.clipmod.capture.FrameCapturer;
import dev.clipmod.capture.FrameEncoder;
import dev.clipmod.capture.Recorder;
import dev.clipmod.capture.ReplayBuffer;
import dev.clipmod.video.ClipWriter;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.Window;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class ClipMod implements ClientModInitializer {
	public static final String MOD_ID = "clipmod";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static ClipConfig config;
	private static Path configFile;
	private static ReplayBuffer buffer;
	private static FrameCapturer capturer;
	private static boolean capturing;
	private static boolean captureBroken;
	private static final ExecutorService SAVER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "ClipMod Saver");
		t.setDaemon(true);
		return t;
	});

	private static KeyBinding saveKey;
	private static KeyBinding toggleKey;
	private static KeyBinding recordKey;
	/** The long recording in progress, or null. Only touched on the client thread. */
	private static Recorder recorder;

	@Override
	public void onInitializeClient() {
		configFile = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + ".json");
		config = ClipConfig.load(configFile);
		buffer = new ReplayBuffer(config.bufferSeconds);
		capturer = new FrameCapturer(new FrameEncoder(buffer));

		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
		saveKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.clipmod.save", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F8, category));
		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.clipmod.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
		recordKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.clipmod.record", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F9, category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (saveKey.wasPressed()) {
				saveClip(config.bufferSeconds, ClipMod::chat);
			}
			while (toggleKey.wasPressed()) {
				setEnabled(!config.enabled, ClipMod::chat);
			}
			while (recordKey.wasPressed()) {
				toggleRecording(ClipMod::chat);
			}
			if (recorder != null && recorder.isFull()) {
				chat(error("Recording reached the 4 GB limit, stopping."));
				stopRecording(ClipMod::chat);
			}
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> registerCommands(dispatcher));

		// warm up the ffmpeg check so the first clip isn't delayed by it
		SAVER.execute(() -> {
			if (config.convertToMp4) {
				String ffmpeg = ClipWriter.findFfmpeg(config.ffmpegPath);
				LOGGER.info("ffmpeg: {}", ffmpeg == null ? "not found, clips will be saved as .avi" : ffmpeg);
			}
		});
		LOGGER.info("Clip Mod ready: keeping the last {}s at {} fps", config.bufferSeconds, config.fps);
	}

	/** Called from {@link dev.clipmod.mixin.WindowMixin} on the render thread before every buffer swap. */
	public static void onFrameEnd(Window window) {
		if (config == null || captureBroken) {
			return;
		}
		try {
			if (!config.enabled && recorder == null) {
				if (capturing) {
					capturer.reset();
					capturing = false;
				}
				return;
			}
			capturing = true;
			capturer.onFrameEnd(window.getFramebufferWidth(), window.getFramebufferHeight(),
					config.fps, config.maxHeight, config.quality);
		} catch (Throwable t) {
			captureBroken = true;
			LOGGER.error("Frame capture failed, clipping is disabled for this session", t);
		}
	}

	// ---------------------------------------------------------------- actions

	private static void saveClip(int seconds, Consumer<Text> feedback) {
		if (captureBroken) {
			feedback.accept(error("Frame capture failed earlier, check the log."));
			return;
		}
		if (!config.enabled) {
			feedback.accept(error("The replay buffer is off. Turn it on with /clip on"));
			return;
		}
		int fps = config.fps;
		List<EncodedFrame> frames = buffer.snapshot(seconds, fps);
		if (frames.isEmpty()) {
			feedback.accept(error("Nothing recorded yet."));
			return;
		}
		double length = frames.size() / (double) fps;
		feedback.accept(info(String.format(Locale.ROOT, "Saving the last %.1fs...", length)));
		Path folder = outputFolder();
		boolean mp4 = config.convertToMp4;
		String ffmpeg = config.ffmpegPath;
		SAVER.execute(() -> {
			try {
				ClipWriter.Result result = ClipWriter.write(frames, fps, folder, mp4, ffmpeg);
				MinecraftClient.getInstance().execute(() -> {
					feedback.accept(prefix().append(Text.literal("Saved ").formatted(Formatting.GREEN))
							.append(fileLink(result.file())));
					if (result.note() != null) {
						feedback.accept(prefix().append(Text.literal(result.note()).formatted(Formatting.YELLOW)));
					}
				});
			} catch (Throwable t) {
				LOGGER.error("Failed to save clip", t);
				MinecraftClient.getInstance().execute(() -> feedback.accept(error("Failed to save clip: " + t.getMessage())));
			}
		});
	}

	private static void toggleRecording(Consumer<Text> feedback) {
		if (recorder == null) {
			startRecording(feedback);
		} else {
			stopRecording(feedback);
		}
	}

	/** Starts a long recording that begins with the replay buffer's last {@code bufferSeconds}. */
	private static void startRecording(Consumer<Text> feedback) {
		if (captureBroken) {
			feedback.accept(error("Frame capture failed earlier, check the log."));
			return;
		}
		if (recorder != null) {
			feedback.accept(error("Already recording. Press " + key(recordKey) + " or /clip stop to finish."));
			return;
		}
		int[] size = buffer.newestSize();
		if (size == null) {
			Window window = MinecraftClient.getInstance().getWindow();
			size = FrameEncoder.outputSize(window.getFramebufferWidth(), window.getFramebufferHeight(), config.maxHeight);
		}
		Recorder rec;
		try {
			rec = new Recorder(outputFolder().resolve(".recording-" + System.currentTimeMillis() + ".tmp"),
					size[0], size[1], config.fps);
		} catch (Exception e) {
			LOGGER.error("Could not start recording", e);
			feedback.accept(error("Could not start recording: " + e.getMessage()));
			return;
		}
		recorder = rec;
		capturer.setForcedSize(size[0], size[1]);
		buffer.setListener(rec::accept);
		List<EncodedFrame> leadIn = config.enabled ? buffer.rawFrames(config.bufferSeconds, size[0], size[1]) : List.of();
		rec.prime(leadIn);
		double seconds = leadIn.isEmpty() ? 0 : (leadIn.get(leadIn.size() - 1).timestampNanos() - leadIn.get(0).timestampNanos()) / 1e9;
		feedback.accept(prefix().append(Text.literal("\u25CF Recording").formatted(Formatting.RED))
				.append(Text.literal(String.format(Locale.ROOT, " (includes the last %.0fs). Press %s or /clip stop to finish.",
						seconds, key(recordKey))).formatted(Formatting.WHITE)));
	}

	private static void stopRecording(Consumer<Text> feedback) {
		Recorder rec = recorder;
		if (rec == null) {
			feedback.accept(error("Not recording. Start with " + key(recordKey) + " or /clip record"));
			return;
		}
		recorder = null;
		buffer.setListener(null);
		capturer.setForcedSize(0, 0);
		feedback.accept(info(String.format(Locale.ROOT, "Stopped recording after %s, saving...", formatTime(rec.elapsedSeconds()))));
		Path folder = outputFolder();
		boolean mp4 = config.convertToMp4;
		String ffmpeg = config.ffmpegPath;
		SAVER.execute(() -> {
			try {
				Recorder.Finished finished = rec.finish();
				double length = finished.durationSeconds();
				ClipWriter.Result result = ClipWriter.writeRecording(finished, folder, mp4, ffmpeg);
				MinecraftClient.getInstance().execute(() -> {
					feedback.accept(prefix().append(Text.literal("Saved " + formatTime(length) + " recording ").formatted(Formatting.GREEN))
							.append(fileLink(result.file())));
					if (result.note() != null) {
						feedback.accept(prefix().append(Text.literal(result.note()).formatted(Formatting.YELLOW)));
					}
				});
			} catch (Throwable t) {
				LOGGER.error("Failed to save recording", t);
				rec.discard();
				MinecraftClient.getInstance().execute(() -> feedback.accept(error("Failed to save recording: " + t.getMessage())));
			}
		});
	}

	private static String formatTime(double seconds) {
		long s = Math.round(seconds);
		return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
				: String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
	}

	private static String key(KeyBinding binding) {
		return binding.getBoundKeyLocalizedText().getString();
	}

	private static void setEnabled(boolean enabled, Consumer<Text> feedback) {
		config.enabled = enabled;
		config.save(configFile);
		if (!enabled) {
			buffer.clear();
		}
		feedback.accept(info(enabled
				? "Replay buffer ON - press " + saveKey.getBoundKeyLocalizedText().getString() + " or /clip to save"
				: "Replay buffer OFF"));
	}

	private static Path outputFolder() {
		if (config.outputFolder != null && !config.outputFolder.isBlank()) {
			return Path.of(config.outputFolder);
		}
		return FabricLoader.getInstance().getGameDir().resolve("clips");
	}

	// ---------------------------------------------------------------- commands

	private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(literal("clip")
				.executes(ctx -> run(ctx, f -> saveClip(config.bufferSeconds, f)))
				.then(argument("seconds", IntegerArgumentType.integer(1, 600))
						.executes(ctx -> run(ctx, f -> {
							int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
							if (seconds > config.bufferSeconds) {
								f.accept(info("Only the last " + config.bufferSeconds + "s are kept; use /clip set length "
										+ seconds + " to keep more."));
							}
							saveClip(Math.min(seconds, config.bufferSeconds), f);
						})))
				.then(literal("record").executes(ctx -> run(ctx, ClipMod::toggleRecording)))
				.then(literal("stop").executes(ctx -> run(ctx, ClipMod::stopRecording)))
				.then(literal("on").executes(ctx -> run(ctx, f -> setEnabled(true, f))))
				.then(literal("off").executes(ctx -> run(ctx, f -> setEnabled(false, f))))
				.then(literal("status").executes(ctx -> run(ctx, ClipMod::status)))
				.then(literal("folder").executes(ctx -> run(ctx, f -> f.accept(prefix()
						.append(Text.literal("Clips folder: ").formatted(Formatting.GRAY))
						.append(fileLink(outputFolder()))))))
				.then(literal("help").executes(ctx -> run(ctx, ClipMod::help)))
				.then(literal("set")
						.then(literal("length").then(argument("seconds", IntegerArgumentType.integer(1, 600))
								.executes(ctx -> run(ctx, f -> {
									config.bufferSeconds = IntegerArgumentType.getInteger(ctx, "seconds");
									buffer.setLengthSeconds(config.bufferSeconds);
									saved(f, "Buffer length", config.bufferSeconds + "s");
								}))))
						.then(literal("fps").then(argument("fps", IntegerArgumentType.integer(1, 60))
								.executes(ctx -> run(ctx, f -> {
									config.fps = IntegerArgumentType.getInteger(ctx, "fps");
									saved(f, "FPS", String.valueOf(config.fps));
								}))))
						.then(literal("resolution").then(argument("height", IntegerArgumentType.integer(0, 4320))
								.executes(ctx -> run(ctx, f -> {
									config.maxHeight = IntegerArgumentType.getInteger(ctx, "height");
									saved(f, "Max height", config.maxHeight == 0 ? "native" : config.maxHeight + "p");
								}))))
						.then(literal("quality").then(argument("quality", FloatArgumentType.floatArg(0.1f, 1.0f))
								.executes(ctx -> run(ctx, f -> {
									config.quality = FloatArgumentType.getFloat(ctx, "quality");
									saved(f, "Quality", String.format(Locale.ROOT, "%.2f", config.quality));
								}))))
						.then(literal("mp4").then(argument("enabled", BoolArgumentType.bool())
								.executes(ctx -> run(ctx, f -> {
									config.convertToMp4 = BoolArgumentType.getBool(ctx, "enabled");
									saved(f, "Convert to mp4", String.valueOf(config.convertToMp4));
								}))))));
	}

	private static int run(CommandContext<FabricClientCommandSource> ctx, Consumer<Consumer<Text>> action) {
		action.accept(ctx.getSource()::sendFeedback);
		return 1;
	}

	private static void saved(Consumer<Text> feedback, String what, String value) {
		config.clamp();
		config.save(configFile);
		feedback.accept(info(what + " set to " + value));
	}

	private static void status(Consumer<Text> f) {
		f.accept(info("Replay buffer: " + (captureBroken ? "BROKEN (see log)" : config.enabled ? "ON" : "OFF")));
		if (recorder != null) {
			f.accept(line(String.format(Locale.ROOT, "Recording: %s so far, %.0f MB on disk",
					formatTime(recorder.elapsedSeconds()), recorder.bytesWritten() / 1048576.0)));
		}
		f.accept(line(String.format(Locale.ROOT, "Buffered: %.1fs / %ds, %d frames, %.1f MB",
				buffer.durationSeconds(), config.bufferSeconds, buffer.frameCount(), buffer.bytes() / 1048576.0)));
		f.accept(line("Capture: " + config.fps + " fps, " + (config.maxHeight == 0 ? "native" : "max " + config.maxHeight + "p")
				+ String.format(Locale.ROOT, ", quality %.2f", config.quality)));
		String ffmpeg = ClipWriter.cachedFfmpeg(config.ffmpegPath);
		f.accept(line("Format: " + (!config.convertToMp4 ? "avi" : ffmpeg == null ? "mp4 (checking ffmpeg...)"
				: !ffmpeg.isEmpty() ? "mp4 (ffmpeg found: " + ffmpeg + ")" : "avi (ffmpeg not found)")));
	}

	private static void help(Consumer<Text> f) {
		f.accept(info("Clip Mod commands:"));
		f.accept(line("/clip - save the last " + config.bufferSeconds + "s (or press " + saveKey.getBoundKeyLocalizedText().getString() + ")"));
		f.accept(line("/clip <seconds> - save the last N seconds"));
		f.accept(line("/clip record - start recording, keeping the last " + config.bufferSeconds + "s (or press " + key(recordKey) + ")"));
		f.accept(line("/clip stop - stop recording and save it"));
		f.accept(line("/clip on | off - start/stop the replay buffer"));
		f.accept(line("/clip status - what's buffered, memory use, format"));
		f.accept(line("/clip folder - open the clips folder"));
		f.accept(line("/clip set length|fps|resolution|quality|mp4 <value>"));
	}

	// ---------------------------------------------------------------- text helpers

	private static void chat(Text text) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.inGameHud != null) {
			client.inGameHud.getChatHud().addMessage(text);
		}
	}

	private static MutableText prefix() {
		return Text.literal("[Clip] ").formatted(Formatting.GOLD);
	}

	private static Text info(String message) {
		return prefix().append(Text.literal(message).formatted(Formatting.WHITE));
	}

	private static Text line(String message) {
		return Text.literal("  " + message).formatted(Formatting.GRAY);
	}

	private static Text error(String message) {
		return prefix().append(Text.literal(message).formatted(Formatting.RED));
	}

	private static Text fileLink(Path path) {
		String absolute = path.toAbsolutePath().toString();
		return Text.literal(path.getFileName().toString()).styled(style -> style
				.withUnderline(true)
				.withColor(Formatting.AQUA)
				.withClickEvent(new ClickEvent.OpenFile(absolute))
				.withHoverEvent(new HoverEvent.ShowText(Text.literal("Click to open\n" + absolute))));
	}
}
