package dev.autodrop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.TreeSet;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Drops the whole stack in chosen inventory slots: opens the inventory, throws each stack
 * (like pressing Ctrl+Q over the slot) and closes it again. Runs on a key, a command, or
 * on a timer.
 *
 * Slot numbers: 1-9 are the hotbar (left to right), 10-36 the main inventory
 * (10-18 top row, 19-27 middle row, 28-36 bottom row).
 */
public final class AutoDrop implements ClientModInitializer {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static final class Config {
		TreeSet<Integer> slots = new TreeSet<>();
		/** Drop automatically every this many seconds (0 = only on key/command). */
		double everySeconds = 0;
		/** Open the inventory screen while dropping (looks like doing it by hand). */
		boolean openInventory = true;
	}

	private static Config config = new Config();
	private static Path configFile;
	private static KeyBinding dropKey;

	// a drop in progress: open -> one throw per tick -> close
	private static final Deque<Integer> queue = new ArrayDeque<>();
	private static boolean openedByUs;
	private static long nextAutoDrop;

	@Override
	public void onInitializeClient() {
		configFile = FabricLoader.getInstance().getConfigDir().resolve("autodrop.json");
		load();
		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("autodrop", "main"));
		dropKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.autodrop.drop", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
		ClientTickEvents.END_CLIENT_TICK.register(AutoDrop::tick);

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(literal("autodrop")
				.executes(ctx -> status(ctx.getSource()))
				.then(literal("add").then(argument("slot", IntegerArgumentType.integer(1, 36)).executes(ctx -> {
					config.slots.add(IntegerArgumentType.getInteger(ctx, "slot"));
					save();
					return status(ctx.getSource());
				})))
				.then(literal("remove").then(argument("slot", IntegerArgumentType.integer(1, 36)).executes(ctx -> {
					config.slots.remove(IntegerArgumentType.getInteger(ctx, "slot"));
					save();
					return status(ctx.getSource());
				})))
				.then(literal("clear").executes(ctx -> {
					config.slots.clear();
					save();
					return status(ctx.getSource());
				}))
				.then(literal("now").executes(ctx -> {
					begin(MinecraftClient.getInstance(), true);
					return 1;
				}))
				.then(literal("every").then(argument("seconds", DoubleArgumentType.doubleArg(0, 86400)).executes(ctx -> {
					config.everySeconds = DoubleArgumentType.getDouble(ctx, "seconds");
					nextAutoDrop = System.nanoTime() + (long) (config.everySeconds * 1e9);
					save();
					return status(ctx.getSource());
				})))
				.then(literal("off").executes(ctx -> {
					config.everySeconds = 0;
					save();
					return status(ctx.getSource());
				}))
				.then(literal("openinventory").then(argument("open", BoolArgumentType.bool()).executes(ctx -> {
					config.openInventory = BoolArgumentType.getBool(ctx, "open");
					save();
					return status(ctx.getSource());
				})))));
	}

	/** Player screen slot id for a friendly slot number (1-9 hotbar, 10-36 main inventory). */
	private static int screenSlot(int slot) {
		return slot <= 9 ? 36 + (slot - 1) : slot - 1; // main inventory 10-36 -> screen slots 9-35
	}

	/** Inventory index (what PlayerInventory.getStack takes) for a friendly slot number. */
	private static int inventoryIndex(int slot) {
		return slot <= 9 ? slot - 1 : slot - 1; // hotbar 0-8, main 9-35
	}

	private static void begin(MinecraftClient client, boolean manual) {
		ClientPlayerEntity player = client.player;
		if (player == null || !queue.isEmpty()) {
			return;
		}
		if (config.slots.isEmpty()) {
			if (manual) {
				player.sendMessage(Text.literal("Auto Drop has no slots. Add one with /autodrop add <slot>").formatted(Formatting.RED), false);
			}
			return;
		}
		if (client.currentScreen != null && !(client.currentScreen instanceof InventoryScreen)) {
			return; // a chest or menu is open; try again later
		}
		for (int slot : config.slots) {
			if (!player.getInventory().getStack(inventoryIndex(slot)).isEmpty()) {
				queue.add(slot);
			}
		}
		if (queue.isEmpty()) {
			return; // nothing to drop
		}
		if (config.openInventory && client.currentScreen == null) {
			client.setScreen(new InventoryScreen(player));
			openedByUs = true;
		}
	}

	private static void tick(MinecraftClient client) {
		while (dropKey.wasPressed()) {
			begin(client, true);
		}
		ClientPlayerEntity player = client.player;
		if (player == null || client.interactionManager == null) {
			queue.clear();
			openedByUs = false;
			return;
		}
		if (config.everySeconds > 0 && queue.isEmpty() && System.nanoTime() >= nextAutoDrop) {
			nextAutoDrop = System.nanoTime() + (long) (config.everySeconds * 1e9);
			begin(client, false);
		}
		if (queue.isEmpty()) {
			return;
		}
		if (client.currentScreen != null && !(client.currentScreen instanceof InventoryScreen)) {
			queue.clear(); // something else opened; stop
			openedByUs = false;
			return;
		}
		// one stack per tick, like clicking through them by hand
		int slot = queue.poll();
		ItemStack stack = player.getInventory().getStack(inventoryIndex(slot));
		if (!stack.isEmpty()) {
			client.interactionManager.clickSlot(player.playerScreenHandler.syncId, screenSlot(slot), 1, SlotActionType.THROW, player);
		}
		if (queue.isEmpty() && openedByUs) {
			player.closeHandledScreen();
			openedByUs = false;
		}
	}

	private static int status(FabricClientCommandSource source) {
		source.sendFeedback(Text.literal("Auto Drop:").formatted(Formatting.GOLD));
		source.sendFeedback(Text.literal("  slots: " + (config.slots.isEmpty() ? "none" : config.slots.toString())).formatted(Formatting.WHITE));
		source.sendFeedback(Text.literal("  automatic: " + (config.everySeconds > 0
				? "every " + String.format(Locale.ROOT, "%s", trim(config.everySeconds)) + "s" : "off")
				+ ", open inventory: " + (config.openInventory ? "yes" : "no")).formatted(Formatting.WHITE));
		source.sendFeedback(Text.literal("  /autodrop add|remove <slot> | clear | now | every <seconds> | off | openinventory true|false").formatted(Formatting.DARK_GRAY));
		source.sendFeedback(Text.literal("  slots: 1-9 hotbar, 10-18 top row, 19-27 middle row, 28-36 bottom row").formatted(Formatting.DARK_GRAY));
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
		if (config.slots == null) {
			config.slots = new TreeSet<>();
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
