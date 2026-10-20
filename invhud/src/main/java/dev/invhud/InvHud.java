package dev.invhud;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
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

/** Shows your main inventory (and optionally armor + offhand) on the HUD. */
public final class InvHud implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("invhud");
	private static final int SLOT = 18;
	private static final int PAD = 3;
	private static final int MARGIN = 4;
	private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

	static InvHudConfig config = new InvHudConfig();
	private static Path configFile;
	private static KeyBinding toggleKey;

	@Override
	public void onInitializeClient() {
		configFile = FabricLoader.getInstance().getConfigDir().resolve("invhud.json");
		config = InvHudConfig.load(configFile);

		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("invhud", "main"));
		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.invhud.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (toggleKey.wasPressed() && client.player != null) {
				config.enabled = !config.enabled;
				save();
				client.player.sendMessage(Text.literal("Inventory HUD: " + (config.enabled ? "ON" : "OFF"))
						.formatted(config.enabled ? Formatting.GREEN : Formatting.RED), true);
			}
		});

		HudElementRegistry.addLast(Identifier.of("invhud", "inventory"), InvHud::render);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(commands()));
	}

	// ---------------------------------------------------------------- drawing

	private static void render(DrawContext context, RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity player = client.player;
		if (!config.enabled || player == null || client.options.hudHidden
				|| (config.hideInMenus && client.currentScreen != null)) {
			return;
		}
		PlayerInventory inventory = player.getInventory();

		int armorWidth = config.armor ? SLOT + PAD : 0;
		int width = PAD * 2 + armorWidth + 9 * SLOT;
		// with armor: a 4-slot armor column on the left, and the offhand under the grid
		int height = PAD * 2 + (config.armor ? 4 : 3) * SLOT;

		float scale = config.scale;
		int screenW = (int) (context.getScaledWindowWidth() / scale);
		int screenH = (int) (context.getScaledWindowHeight() / scale);
		int x = switch (config.corner) {
			case TOP_LEFT, BOTTOM_LEFT -> MARGIN;
			case TOP_RIGHT, BOTTOM_RIGHT -> screenW - width - MARGIN;
		};
		int y = switch (config.corner) {
			case TOP_LEFT, TOP_RIGHT -> MARGIN;
			case BOTTOM_LEFT, BOTTOM_RIGHT -> screenH - height - MARGIN;
		};

		context.getMatrices().pushMatrix();
		context.getMatrices().scale(scale, scale);
		try {
			if (config.background > 0) {
				int alpha = Math.round(config.background * 2.55f);
				context.fill(x, y, x + width, y + height, alpha << 24);
			}
			int gridX = x + PAD + armorWidth;
			int gridY = y + PAD;
			if (config.armor) {
				// helmet to boots down the left column, offhand below the first grid column
				for (int i = 0; i < 4; i++) {
					slot(context, client, player.getEquippedStack(ARMOR[i]), x + PAD, gridY + i * SLOT);
				}
				slot(context, client, player.getOffHandStack(), gridX, gridY + 3 * SLOT);
			}
			// main inventory: indices 9-35, three rows of nine
			for (int row = 0; row < 3; row++) {
				for (int col = 0; col < 9; col++) {
					ItemStack stack = inventory.getStack(9 + row * 9 + col);
					slot(context, client, stack, gridX + col * SLOT, gridY + row * SLOT);
				}
			}
		} finally {
			context.getMatrices().popMatrix();
		}
	}

	private static void slot(DrawContext context, MinecraftClient client, ItemStack stack, int x, int y) {
		context.fill(x, y, x + SLOT - 1, y + SLOT - 1, 0x30FFFFFF);
		if (!stack.isEmpty()) {
			context.drawItem(stack, x + 1, y + 1);
			context.drawStackOverlay(client.textRenderer, stack, x + 1, y + 1);
		}
	}

	// ---------------------------------------------------------------- commands

	private static LiteralArgumentBuilder<FabricClientCommandSource> commands() {
		LiteralArgumentBuilder<FabricClientCommandSource> position = literal("position");
		for (InvHudConfig.Corner corner : InvHudConfig.Corner.values()) {
			position.then(literal(corner.pretty()).executes(ctx -> {
				config.corner = corner;
				return changed(ctx.getSource());
			}));
		}
		return literal("invhud")
				.executes(ctx -> show(ctx.getSource()))
				.then(literal("on").executes(ctx -> {
					config.enabled = true;
					return changed(ctx.getSource());
				}))
				.then(literal("off").executes(ctx -> {
					config.enabled = false;
					return changed(ctx.getSource());
				}))
				.then(position)
				.then(literal("scale").then(argument("size", FloatArgumentType.floatArg(0.25f, 3.0f)).executes(ctx -> {
					config.scale = FloatArgumentType.getFloat(ctx, "size");
					return changed(ctx.getSource());
				})))
				.then(literal("background").then(argument("percent", IntegerArgumentType.integer(0, 100)).executes(ctx -> {
					config.background = IntegerArgumentType.getInteger(ctx, "percent");
					return changed(ctx.getSource());
				})))
				.then(literal("armor").then(argument("show", BoolArgumentType.bool()).executes(ctx -> {
					config.armor = BoolArgumentType.getBool(ctx, "show");
					return changed(ctx.getSource());
				})))
				.then(literal("hideinmenus").then(argument("hide", BoolArgumentType.bool()).executes(ctx -> {
					config.hideInMenus = BoolArgumentType.getBool(ctx, "hide");
					return changed(ctx.getSource());
				})))
				.then(literal("reset").executes(ctx -> {
					config = new InvHudConfig();
					return changed(ctx.getSource());
				}));
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
		source.sendFeedback(Text.literal("Inventory HUD:").formatted(Formatting.GOLD));
		source.sendFeedback(line("shown", config.enabled ? "on" : "off", "/invhud on|off"));
		source.sendFeedback(line("position", config.corner.pretty(), "/invhud position top-left|top-right|bottom-left|bottom-right"));
		source.sendFeedback(line("scale", String.format(Locale.ROOT, "%.2fx", config.scale), "/invhud scale 0.25-3"));
		source.sendFeedback(line("background", config.background + "%", "/invhud background 0-100"));
		source.sendFeedback(line("armor", config.armor ? "shown" : "hidden", "/invhud armor true|false"));
		source.sendFeedback(line("hide in menus", config.hideInMenus ? "yes" : "no", "/invhud hideinmenus true|false"));
		return 1;
	}

	private static Text line(String name, String value, String usage) {
		return Text.literal("  " + name + ": ").formatted(Formatting.GRAY)
				.append(Text.literal(value).formatted(Formatting.WHITE))
				.append(Text.literal("   " + usage).formatted(Formatting.DARK_GRAY));
	}
}
