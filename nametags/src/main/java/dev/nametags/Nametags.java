package dev.nametags;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Locale;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class Nametags implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("nametags");
	public static NametagConfig config = new NametagConfig();
	private static Path configFile;
	private static KeyBinding ownKey;

	@Override
	public void onInitializeClient() {
		configFile = FabricLoader.getInstance().getConfigDir().resolve("nametags.json");
		config = NametagConfig.load(configFile);

		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("nametags", "main"));
		ownKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.nametags.own", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (ownKey.wasPressed() && client.player != null) {
				config.showOwn = !config.showOwn;
				save();
				client.player.sendMessage(Text.literal("Own nametag: " + (config.showOwn ? "ON" : "OFF"))
						.formatted(config.showOwn ? Formatting.GREEN : Formatting.RED), true);
			}
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(literal("nametags")
				.executes(ctx -> show(ctx.getSource()))
				.then(literal("own").then(argument("on", BoolArgumentType.bool()).executes(ctx -> {
					config.showOwn = BoolArgumentType.getBool(ctx, "on");
					return changed(ctx.getSource());
				})))
				.then(literal("shadow").then(argument("on", BoolArgumentType.bool()).executes(ctx -> {
					config.textShadow = BoolArgumentType.getBool(ctx, "on");
					return changed(ctx.getSource());
				})))
				.then(literal("background")
						.then(literal("default").executes(ctx -> {
							config.backgroundOpacity = -1;
							return changed(ctx.getSource());
						}))
						.then(argument("percent", IntegerArgumentType.integer(0, 100)).executes(ctx -> {
							config.backgroundOpacity = IntegerArgumentType.getInteger(ctx, "percent");
							return changed(ctx.getSource());
						})))
				.then(literal("scale").then(argument("size", FloatArgumentType.floatArg(0.25f, 4.0f)).executes(ctx -> {
					config.scale = FloatArgumentType.getFloat(ctx, "size");
					return changed(ctx.getSource());
				})))
				.then(literal("reset").executes(ctx -> {
					config = new NametagConfig();
					return changed(ctx.getSource());
				}))));
	}

	/** True if {@code entity} is you and your own nametag should show (third person only). */
	public static boolean shouldShowOwn(Entity entity) {
		MinecraftClient client = MinecraftClient.getInstance();
		return config.showOwn
				&& entity == client.player
				&& entity == client.getCameraEntity()
				&& !client.options.getPerspective().isFirstPerson()
				&& MinecraftClient.isHudEnabled()
				&& !entity.isInvisible();
	}

	private static void save() {
		config.clamp();
		config.save(configFile);
	}

	private static int changed(FabricClientCommandSource source) {
		save();
		return show(source);
	}

	private static int show(FabricClientCommandSource source) {
		source.sendFeedback(Text.literal("Nametags:").formatted(Formatting.GOLD));
		source.sendFeedback(line("own", onOff(config.showOwn), "/nametags own true|false"));
		source.sendFeedback(line("shadow", onOff(config.textShadow), "/nametags shadow true|false"));
		source.sendFeedback(line("background", config.backgroundOpacity < 0 ? "default" : config.backgroundOpacity + "%",
				"/nametags background 0-100|default"));
		source.sendFeedback(line("scale", String.format(Locale.ROOT, "%.2fx", config.scale), "/nametags scale 0.25-4"));
		return 1;
	}

	private static Text line(String name, String value, String usage) {
		return Text.literal("  " + name + ": ").formatted(Formatting.GRAY)
				.append(Text.literal(value).formatted(Formatting.WHITE))
				.append(Text.literal("   " + usage).formatted(Formatting.DARK_GRAY));
	}

	private static String onOff(boolean b) {
		return b ? "on" : "off";
	}
}
