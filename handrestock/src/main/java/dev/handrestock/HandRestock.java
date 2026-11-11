package dev.handrestock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Refills the selected hotbar slot from the inventory when the held stack runs out.
 * <p>
 * Besides stacks that run out completely (blocks, food, arrows, broken tools), it also
 * handles items that turn into a container when used up: drinking milk or placing water
 * leaves an empty bucket, potions leave a glass bottle, and stews leave a bowl. In that
 * case the next matching item is swapped in and the empty container moves to where that
 * item was.
 * <p>
 * The swap is a normal inventory click, so it works in singleplayer and on servers.
 */
public final class HandRestock implements ClientModInitializer {
	/** Items that are left over in the hand after the original item is used up. */
	private static final Set<Item> LEFTOVERS = Set.of(Items.BUCKET, Items.GLASS_BOTTLE, Items.BOWL);
	/** Ticks to wait after the item runs out, so the server has finished using it before we swap. */
	private static final int DELAY_TICKS = 2;

	private static boolean enabled = true;
	private static KeyBinding toggleKey;

	/** Settings stored in {@code config/handrestock.json}. */
	public static final class Config {
		/** Switch to the next hotbar slot when the held stack gets down to this many (0 = off). */
		int switchAt = 0;
		/** Only switch to a slot holding the same item (true) or to the next filled slot (false). */
		boolean sameItemOnly = true;
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Config config = new Config();
	private static Path configFile;

	// what was in the hand last tick
	private ItemStack lastStack = ItemStack.EMPTY;
	private int lastSlot = -1;
	private boolean lastScreenOpen = true;

	// a restock waiting for DELAY_TICKS to pass
	private ItemStack pendingItem = ItemStack.EMPTY;
	private int pendingSlot = -1;
	private int pendingTicks;

	@Override
	public void onInitializeClient() {
		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("handrestock", "main"));
		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.handrestock.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);

		configFile = FabricLoader.getInstance().getConfigDir().resolve("handrestock.json");
		load();
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(literal("restock")
				.executes(ctx -> status(ctx.getSource()))
				.then(literal("switchat").then(argument("amount", IntegerArgumentType.integer(0, 64)).executes(ctx -> {
					config.switchAt = IntegerArgumentType.getInteger(ctx, "amount");
					save();
					return status(ctx.getSource());
				})))
				.then(literal("off").executes(ctx -> {
					config.switchAt = 0;
					save();
					return status(ctx.getSource());
				}))
				.then(literal("sameitem").executes(ctx -> {
					config.sameItemOnly = true;
					save();
					return status(ctx.getSource());
				}))
				.then(literal("anyitem").executes(ctx -> {
					config.sameItemOnly = false;
					save();
					return status(ctx.getSource());
				}))));
	}

	private void tick(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player == null || client.interactionManager == null) {
			lastStack = ItemStack.EMPTY;
			lastSlot = -1;
			pendingSlot = -1;
			return;
		}
		while (toggleKey.wasPressed()) {
			enabled = !enabled;
			player.sendMessage(Text.literal("Hand Restock: " + (enabled ? "ON" : "OFF"))
					.formatted(enabled ? Formatting.GREEN : Formatting.RED), true);
		}

		PlayerInventory inventory = player.getInventory();
		int slot = inventory.getSelectedSlot();
		ItemStack current = player.getMainHandStack();
		boolean screenOpen = client.currentScreen != null;

		if (enabled && !player.isSpectator() && !screenOpen && !lastScreenOpen && slot == lastSlot
				&& ranOut(lastStack, current)) {
			pendingItem = lastStack.copy();
			pendingSlot = slot;
			pendingTicks = DELAY_TICKS;
		}

		if (pendingSlot != -1) {
			if (screenOpen || slot != pendingSlot || !(current.isEmpty() || LEFTOVERS.contains(current.getItem()))) {
				pendingSlot = -1; // player did something else; don't interfere
			} else if (--pendingTicks <= 0) {
				restock(client, player, pendingItem, pendingSlot);
				pendingSlot = -1;
			}
		}

		if (enabled && config.switchAt > 0 && !screenOpen && pendingSlot == -1 && !player.isSpectator()) {
			switchBeforeEmpty(inventory);
		}

		lastStack = player.getMainHandStack().copy();
		lastSlot = inventory.getSelectedSlot();
		lastScreenOpen = screenOpen;
	}

	/**
	 * When the held stack is down to {@code switchAt}, select the next hotbar slot (to the right,
	 * wrapping around) that holds the same item with more than that left, or with
	 * {@code sameItemOnly} off, the next filled slot. The server is told about the new slot the
	 * same way as when you scroll.
	 */
	private static void switchBeforeEmpty(PlayerInventory inventory) {
		int selected = inventory.getSelectedSlot();
		ItemStack held = inventory.getStack(selected);
		if (held.isEmpty() || !held.isStackable() || held.getCount() > config.switchAt) {
			return;
		}
		for (int d = 1; d < 9; d++) {
			int i = (selected + d) % 9;
			ItemStack candidate = inventory.getStack(i);
			if (candidate.isEmpty()) {
				continue;
			}
			boolean same = ItemStack.areItemsAndComponentsEqual(candidate, held);
			if (config.sameItemOnly && !same) {
				continue;
			}
			if (same && candidate.getCount() <= config.switchAt) {
				continue; // also nearly empty; switching to it would just bounce back
			}
			inventory.setSelectedSlot(i);
			return;
		}
	}

	private static int status(FabricClientCommandSource source) {
		source.sendFeedback(Text.literal("Hand Restock: " + (enabled ? "ON" : "OFF")).formatted(Formatting.GOLD));
		source.sendFeedback(Text.literal(config.switchAt > 0
				? "  switch slots at " + config.switchAt + " left, to " + (config.sameItemOnly ? "the same item" : "the next filled slot")
				: "  early slot switching: off").formatted(Formatting.WHITE));
		source.sendFeedback(Text.literal("  /restock switchat <amount> | off | sameitem | anyitem").formatted(Formatting.DARK_GRAY));
		return 1;
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

	/** True if the hand went from {@code before} to empty, or to the container {@code before} leaves behind. */
	private static boolean ranOut(ItemStack before, ItemStack now) {
		if (before.isEmpty()) {
			return false;
		}
		if (now.isEmpty()) {
			return true;
		}
		return LEFTOVERS.contains(now.getItem()) && !before.isOf(now.getItem());
	}

	private static void restock(MinecraftClient client, ClientPlayerEntity player, ItemStack wanted, int hotbarSlot) {
		PlayerInventory inventory = player.getInventory();
		int source = findReplacement(inventory, wanted, hotbarSlot);
		if (source == -1) {
			return;
		}
		// player screen slot ids: 9-35 main inventory (same as inventory index), 36-44 hotbar
		int slotId = source < 9 ? 36 + source : source;
		client.interactionManager.clickSlot(player.playerScreenHandler.syncId, slotId, hotbarSlot, SlotActionType.SWAP, player);
	}

	/** Inventory index of the best replacement for {@code wanted}, or -1. Main inventory first, then the hotbar. */
	private static int findReplacement(PlayerInventory inventory, ItemStack wanted, int hotbarSlot) {
		int sameItem = -1;
		for (int pass = 0; pass < 2; pass++) {
			int from = pass == 0 ? 9 : 0;
			int to = pass == 0 ? 36 : 9;
			for (int i = from; i < to; i++) {
				if (i == hotbarSlot) {
					continue;
				}
				ItemStack stack = inventory.getStack(i);
				if (stack.isEmpty() || !stack.isOf(wanted.getItem())) {
					continue;
				}
				if (ItemStack.areItemsAndComponentsEqual(stack, wanted)) {
					return i; // exact match (same potion type, same enchantments, ...)
				}
				// tools differ by durability, so any tool of the same kind will do
				if (sameItem == -1 && wanted.isDamageable()) {
					sameItem = i;
				}
			}
		}
		return sameItem;
	}
}
