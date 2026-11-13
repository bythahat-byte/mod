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
import net.minecraft.client.network.ClientPlayerEntity;
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

	/**
	 * One step: press {@code key} (tap, or hold it down for {@code holdSeconds}), then wait
	 * {@code waitSeconds} before the next step. A step with no key is just a pause.
	 */
	public static final class Step {
		String key;
		double holdSeconds;
		double waitSeconds;
		/** For "move" steps: walk this many blocks with a movement key (0 = not a move step). */
		double blocks;
		/** Inventory steps: "refill" (item -> hotbar) or "crafthay" (hay bales -> wheat). */
		String action;
		String item;
		/** Inventory steps: how many stacks to handle (0 = all). */
		int stacks;
		/** crafthay: only run when you have fewer than this much wheat (0 = always). */
		int wheatBelow;

		Step(String key, double holdSeconds, double waitSeconds) {
			this.key = key;
			this.holdSeconds = holdSeconds;
			this.waitSeconds = waitSeconds;
		}

		static Step move(String key, double blocks, double waitSeconds) {
			Step step = new Step(key, 0, waitSeconds);
			step.blocks = blocks;
			return step;
		}

		static Step inventory(String action, String item, double waitSeconds) {
			Step step = new Step(null, 0, waitSeconds);
			step.action = action;
			step.item = item;
			return step;
		}

		Step withStacks(int stacks) {
			this.stacks = stacks;
			return this;
		}

		Step withWheatBelow(int wheatBelow) {
			this.wheatBelow = wheatBelow;
			return this;
		}

		Step copy() {
			Step step = new Step(key, holdSeconds, waitSeconds);
			step.blocks = blocks;
			step.action = action;
			step.item = item;
			step.stacks = stacks;
			step.wheatBelow = wheatBelow;
			return step;
		}
	}

	/** A saved list of steps. */
	public static final class Saved {
		List<Step> steps = new ArrayList<>();
		boolean loop = true;
	}

	public static final class Config {
		List<Step> steps = new ArrayList<>();
		/** Start over from the first step after the last one. */
		boolean loop = true;
		/** Named saves, e.g. "1" or "cowfarm". */
		java.util.Map<String, Saved> saves = new java.util.TreeMap<>();
	}

	private static List<Step> copySteps(List<Step> steps) {
		List<Step> out = new ArrayList<>();
		for (Step st : steps) {
			out.add(st.copy());
		}
		return out;
	}

	private static com.mojang.brigadier.suggestion.SuggestionProvider<FabricClientCommandSource> saveNames() {
		return (ctx, builder) -> net.minecraft.command.CommandSource.suggestMatching(config.saves.keySet(), builder);
	}

	private static Config config = new Config();
	private static Path configFile;
	private static KeyBinding startStopKey;

	private static boolean running;
	private static int stepIndex;
	private static long nextStepAt;
	/** Key tapped this tick, released on the next one. */
	private static InputUtil.Key heldKey;
	/** Key being held down for a "hold" step, and when to let go. */
	private static InputUtil.Key holdingKey;
	private static long holdUntil;

	// "move" steps: walk until the target block centre, then let go so the slide stops you on it
	/**
	 * After letting go, ground friction keeps you sliding: each tick you move by your velocity and
	 * it shrinks to 0.546x (0.6 block slipperiness x 0.91). That adds up to velocity / (1 - 0.546).
	 */
	private static final double SLIDE_FACTOR = 1.0 / (1.0 - 0.546);
	/** Inventory work for the current step, one click per tick; returns false when finished. */
	private static java.util.function.BooleanSupplier inventoryWork;
	private static int inventoryDelay;
	private static long inventoryWaitNanos;
	private static boolean openedInventory;

	private static boolean moving;
	private static boolean moveOnX;
	private static int moveSign;
	private static double moveTarget;
	private static long moveDeadline;
	private static long moveWaitNanos;

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
							config.steps.add(new Step(key.toLowerCase(Locale.ROOT), 0, DoubleArgumentType.getDouble(ctx, "waitSeconds")));
							save();
							return list(ctx.getSource());
						}))))
				.then(literal("hold").then(argument("key", StringArgumentType.word())
						.then(argument("holdSeconds", DoubleArgumentType.doubleArg(0.05, 86400))
								.then(argument("waitSeconds", DoubleArgumentType.doubleArg(0, 86400)).executes(ctx -> {
									String key = StringArgumentType.getString(ctx, "key");
									if (parseKey(key) == null) {
										ctx.getSource().sendError(Text.literal("Unknown key \"" + key
												+ "\". Use things like 1-9, a-z, space, left (mouse), right (mouse), f, q, e."));
										return 0;
									}
									config.steps.add(new Step(key.toLowerCase(Locale.ROOT),
											DoubleArgumentType.getDouble(ctx, "holdSeconds"), DoubleArgumentType.getDouble(ctx, "waitSeconds")));
									save();
									return list(ctx.getSource());
								})))))
				.then(literal("wait").then(argument("seconds", DoubleArgumentType.doubleArg(0, 86400)).executes(ctx -> {
					config.steps.add(new Step(null, 0, DoubleArgumentType.getDouble(ctx, "seconds")));
					save();
					return list(ctx.getSource());
				})))
				.then(moveForm((ctx, step) -> {
					config.steps.add(step);
					save();
					return list(ctx.getSource());
				}))
				.then(refillForm((ctx, step) -> {
					config.steps.add(step);
					save();
					return list(ctx.getSource());
				}))
				.then(craftHayForm((ctx, step) -> {
					config.steps.add(step);
					save();
					return list(ctx.getSource());
				}))
				.then(literal("repeat").then(argument("times", IntegerArgumentType.integer(2, 1000))
						.then(argument("lastSteps", IntegerArgumentType.integer(1, 1000)).executes(ctx -> {
							int times = IntegerArgumentType.getInteger(ctx, "times");
							int last = IntegerArgumentType.getInteger(ctx, "lastSteps");
							if (last > config.steps.size()) {
								ctx.getSource().sendError(Text.literal("There are only " + config.steps.size() + " steps"));
								return 0;
							}
							if (config.steps.size() + last * (times - 1) > 5000) {
								ctx.getSource().sendError(Text.literal("That would make too many steps (over 5000)"));
								return 0;
							}
							// the last N steps end up happening `times` times in total
							List<Step> block = new ArrayList<>(config.steps.subList(config.steps.size() - last, config.steps.size()));
							for (int t = 1; t < times; t++) {
								for (Step st : block) {
									config.steps.add(st.copy());
								}
							}
							save();
							return list(ctx.getSource());
						}))))
				.then(literal("edit").then(stepForms(argument("step", IntegerArgumentType.integer(1)), (ctx, step) -> {
					int i = IntegerArgumentType.getInteger(ctx, "step") - 1;
					if (i >= config.steps.size()) {
						ctx.getSource().sendError(Text.literal("There is no step " + (i + 1)));
						return 0;
					}
					stop(null);
					config.steps.set(i, step);
					save();
					ctx.getSource().sendFeedback(Text.literal("Step " + (i + 1) + " is now: " + describe(step)).formatted(Formatting.GREEN));
					return 1;
				})))
				.then(literal("insert").then(stepForms(argument("step", IntegerArgumentType.integer(1)), (ctx, step) -> {
					int i = IntegerArgumentType.getInteger(ctx, "step") - 1;
					if (i > config.steps.size()) {
						ctx.getSource().sendError(Text.literal("There are only " + config.steps.size() + " steps"));
						return 0;
					}
					stop(null);
					config.steps.add(i, step);
					save();
					ctx.getSource().sendFeedback(Text.literal("Inserted step " + (i + 1) + ": " + describe(step)).formatted(Formatting.GREEN));
					return 1;
				})))
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
				}).then(argument("save", StringArgumentType.word()).suggests(saveNames()).executes(ctx -> {
					if (!loadSave(StringArgumentType.getString(ctx, "save"), ctx.getSource())) {
						return 0;
					}
					start(ctx.getSource()::sendFeedback);
					return 1;
				})))
				.then(literal("save").then(argument("name", StringArgumentType.word()).suggests(saveNames()).executes(ctx -> {
					String name = StringArgumentType.getString(ctx, "name");
					if (config.steps.isEmpty()) {
						ctx.getSource().sendError(Text.literal("There are no steps to save"));
						return 0;
					}
					Saved saved = new Saved();
					saved.steps = copySteps(config.steps);
					saved.loop = config.loop;
					boolean replaced = config.saves.put(name, saved) != null;
					save();
					ctx.getSource().sendFeedback(Text.literal((replaced ? "Overwrote" : "Saved") + " \"" + name + "\" ("
							+ saved.steps.size() + " steps)").formatted(Formatting.GREEN));
					return 1;
				})))
				.then(literal("load").then(argument("name", StringArgumentType.word()).suggests(saveNames()).executes(ctx -> {
					if (!loadSave(StringArgumentType.getString(ctx, "name"), ctx.getSource())) {
						return 0;
					}
					return list(ctx.getSource());
				})))
				.then(literal("saves").executes(ctx -> {
					FabricClientCommandSource src = ctx.getSource();
					if (config.saves.isEmpty()) {
						src.sendFeedback(Text.literal("No saves yet. Save the current list with /keytimer save <name>").formatted(Formatting.GRAY));
						return 1;
					}
					src.sendFeedback(Text.literal("Key Timer saves:").formatted(Formatting.GOLD));
					for (java.util.Map.Entry<String, Saved> e : config.saves.entrySet()) {
						double total = 0;
						for (Step st : e.getValue().steps) {
							total += st.holdSeconds + st.waitSeconds + st.blocks / 4.317;
						}
						src.sendFeedback(Text.literal(String.format(Locale.ROOT, "  %s: %d steps, about %s%s",
								e.getKey(), e.getValue().steps.size(), duration(total), e.getValue().loop ? ", loops" : ""))
								.formatted(Formatting.WHITE));
					}
					src.sendFeedback(Text.literal("  /keytimer load <name> | start <name> | delete <name>").formatted(Formatting.DARK_GRAY));
					return 1;
				}))
				.then(literal("delete").then(argument("name", StringArgumentType.word()).suggests(saveNames()).executes(ctx -> {
					String name = StringArgumentType.getString(ctx, "name");
					if (config.saves.remove(name) == null) {
						ctx.getSource().sendError(Text.literal("There is no save called \"" + name + "\""));
						return 0;
					}
					save();
					ctx.getSource().sendFeedback(Text.literal("Deleted save \"" + name + "\"").formatted(Formatting.YELLOW));
					return 1;
				})))
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
		long now = System.nanoTime();
		if (inventoryWork != null) {
			if (client.currentScreen != null && !(client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.InventoryScreen)) {
				finishInventory(client, now); // something else opened; skip the rest
				return;
			}
			if (inventoryDelay > 0) {
				inventoryDelay--;
				return;
			}
			if (!inventoryWork.getAsBoolean()) {
				finishInventory(client, now);
			}
			return;
		}
		if (holdingKey != null && moving) {
			ClientPlayerEntity player = client.player;
			double pos = moveOnX ? player.getX() : player.getZ();
			double vel = Math.max(0, (moveOnX ? player.getVelocity().x : player.getVelocity().z) * moveSign);
			double error = (moveTarget - pos) * moveSign - vel * SLIDE_FACTOR; // where we'd stop if we let go now
			// keep going while one more tick (moving about vel / 0.546) gets the stop closer to the target
			if (error > vel / 0.546 / 2 && now < moveDeadline) {
				KeyBinding.setKeyPressed(holdingKey, true);
				return;
			}
			KeyBinding.setKeyPressed(holdingKey, false);
			holdingKey = null;
			moving = false;
			nextStepAt = now + moveWaitNanos;
		}
		if (holdingKey != null) {
			if (now < holdUntil) {
				KeyBinding.setKeyPressed(holdingKey, true); // keep it down even if a real key event reset it
				return;
			}
			KeyBinding.setKeyPressed(holdingKey, false);
			holdingKey = null;
		}
		if (now < nextStepAt) {
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
		if (step.action != null) {
			stepIndex++;
			startInventory(client, step);
			return;
		}
		InputUtil.Key key = step.key == null ? null : parseKey(step.key);
		long start = now;
		if (key != null) {
			// same as a real key press: counts as "pressed" once and is held down
			KeyBinding.setKeyPressed(key, true);
			KeyBinding.onKeyPressed(key);
			if (step.blocks > 0) {
				startMove(client.player, step);
				holdingKey = key;
				moving = true;
				moveWaitNanos = (long) (step.waitSeconds * 1_000_000_000L);
				nextStepAt = Long.MAX_VALUE; // set when the move finishes
				stepIndex++;
				return;
			} else if (step.holdSeconds > 0) {
				holdingKey = key;
				holdUntil = now + (long) (step.holdSeconds * 1_000_000_000L);
				start = holdUntil; // the wait starts once the key is let go
			} else {
				heldKey = key; // tap: released next tick
			}
		}
		nextStepAt = start + (long) (step.waitSeconds * 1_000_000_000L);
		stepIndex++;
	}

	// ---------------------------------------------------------------- inventory steps

	private static void startInventory(MinecraftClient client, Step step) {
		ClientPlayerEntity player = client.player;
		inventoryWaitNanos = (long) (step.waitSeconds * 1_000_000_000L);
		nextStepAt = Long.MAX_VALUE;
		if (client.currentScreen != null && !(client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.InventoryScreen)) {
			nextStepAt = System.nanoTime() + inventoryWaitNanos; // a chest or menu is open: skip this step
			return;
		}
		if ("crafthay".equals(step.action) && step.wheatBelow > 0 && countItem(player.getInventory(), net.minecraft.item.Items.WHEAT) >= step.wheatBelow) {
			nextStepAt = System.nanoTime() + inventoryWaitNanos; // still have enough wheat: nothing to do
			return;
		}
		openedInventory = false;
		if (client.currentScreen == null) {
			client.setScreen(new net.minecraft.client.gui.screen.ingame.InventoryScreen(player));
			openedInventory = true;
		}
		inventoryDelay = 2; // let the screen open
		if ("refill".equals(step.action)) {
			net.minecraft.item.Item item = parseItem(step.item);
			int[] guard = {0};
			int limit = step.stacks > 0 ? step.stacks : 40;
			inventoryWork = () -> item != null && guard[0]++ < limit && refillOnce(client, item);
		} else if ("crafthay".equals(step.action)) {
			int[] stage = {0, 0}; // [routine stage, hay stacks started]
			int[] guard = {0};
			int limit = step.stacks > 0 ? step.stacks : Integer.MAX_VALUE;
			inventoryWork = () -> guard[0]++ < 400 && craftHayStep(client, stage, limit);
		} else {
			inventoryWork = () -> false;
		}
	}

	private static void finishInventory(MinecraftClient client, long now) {
		inventoryWork = null;
		if (openedInventory && client.player != null
				&& client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.InventoryScreen) {
			client.player.closeHandledScreen();
		}
		openedInventory = false;
		nextStepAt = now + inventoryWaitNanos;
	}

	private static void click(MinecraftClient client, int slotId, int button, net.minecraft.screen.slot.SlotActionType type) {
		ClientPlayerEntity player = client.player;
		client.interactionManager.clickSlot(player.playerScreenHandler.syncId, slotId, button, type, player);
	}

	/**
	 * Shift-clicks one stack of {@code item} from the main inventory (screen slots 9-35) into the
	 * hotbar, if the hotbar has room for it. Returns false when there's nothing more to move.
	 */
	private static boolean refillOnce(MinecraftClient client, net.minecraft.item.Item item) {
		net.minecraft.entity.player.PlayerInventory inv = client.player.getInventory();
		for (int i = 9; i < 36; i++) {
			net.minecraft.item.ItemStack stack = inv.getStack(i);
			if (stack.isEmpty() || !stack.isOf(item) || !hotbarHasRoomFor(inv, stack)) {
				continue;
			}
			click(client, i, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE); // inventory index 9-35 = screen slot 9-35
			return true;
		}
		return false;
	}

	private static boolean hotbarHasRoomFor(net.minecraft.entity.player.PlayerInventory inv, net.minecraft.item.ItemStack stack) {
		for (int i = 0; i < 9; i++) {
			net.minecraft.item.ItemStack h = inv.getStack(i);
			if (h.isEmpty() || (net.minecraft.item.ItemStack.areItemsAndComponentsEqual(h, stack) && h.getCount() < h.getMaxCount())) {
				return true;
			}
		}
		return false;
	}

	private static int countItem(net.minecraft.entity.player.PlayerInventory inv, net.minecraft.item.Item item) {
		int n = 0;
		for (int i = 0; i < 36; i++) {
			if (inv.getStack(i).isOf(item)) {
				n += inv.getStack(i).getCount();
			}
		}
		return n;
	}

	/** Room for wheat in the inventory (main + hotbar), in items. */
	private static int wheatSpace(net.minecraft.entity.player.PlayerInventory inv) {
		int space = 0;
		for (int i = 0; i < 36; i++) {
			net.minecraft.item.ItemStack h = inv.getStack(i);
			if (h.isEmpty()) {
				space += 64;
			} else if (h.isOf(net.minecraft.item.Items.WHEAT)) {
				space += h.getMaxCount() - h.getCount();
			}
		}
		return space;
	}

	/**
	 * One click of the hay -> wheat routine, using the inventory's 2x2 crafting grid
	 * (screen slot 1 = top-left input, 0 = result):
	 * 0 pick up a hay stack, 1 put it in the grid, 2 shift-click the result (crafts as many as
	 * fit), 3 shift-click leftover hay back. Repeats while there's room for at least 9 wheat.
	 */
	private static boolean craftHayStep(MinecraftClient client, int[] stage, int maxStacks) {
		net.minecraft.entity.player.PlayerInventory inv = client.player.getInventory();
		net.minecraft.screen.slot.SlotActionType pickup = net.minecraft.screen.slot.SlotActionType.PICKUP;
		switch (stage[0]) {
			case 0 -> {
				if (wheatSpace(inv) < 9 || stage[1] >= maxStacks) {
					return false;
				}
				for (int i = 0; i < 36; i++) {
					net.minecraft.item.ItemStack hay = inv.getStack(i);
					if (hay.isOf(net.minecraft.item.Items.HAY_BLOCK)) {
						// only if all of this stack's wheat fits (its own slot frees up once picked up)
						if (wheatSpace(inv) + 64 < hay.getCount() * 9) {
							return false;
						}
						int screenSlot = i < 9 ? 36 + i : i;
						click(client, screenSlot, 0, pickup); // pick up the stack
						stage[0] = 1;
						stage[1]++;
						return true;
					}
				}
				return false; // no hay left
			}
			case 1 -> {
				click(client, 1, 0, pickup); // drop it into the crafting grid
				stage[0] = 2;
				inventoryDelay = 2; // give the server a moment to work out the result
				return true;
			}
			case 2 -> {
				click(client, 0, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE); // craft as much as fits
				stage[0] = 3;
				inventoryDelay = 1;
				return true;
			}
			default -> {
				click(client, 1, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE); // leftover hay back to the inventory
				stage[0] = 0;
				inventoryDelay = 1;
				return true;
			}
		}
	}

	private static net.minecraft.item.Item parseItem(String name) {
		if (name == null) {
			return null;
		}
		net.minecraft.util.Identifier id = net.minecraft.util.Identifier.tryParse(name.contains(":") ? name : "minecraft:" + name);
		if (id == null || !net.minecraft.registry.Registries.ITEM.containsId(id)) {
			return null;
		}
		return net.minecraft.registry.Registries.ITEM.get(id);
	}

	/**
	 * Works out which way the movement key takes you (from where you're facing), snaps to the
	 * nearest block axis, and aims for the centre of the block {@code blocks} away from the
	 * centre of the block you're standing in. Aiming at block centres means small errors
	 * don't add up from one move to the next.
	 */
	private static void startMove(ClientPlayerEntity player, Step step) {
		double yaw = Math.toRadians(player.getYaw());
		double fx = -Math.sin(yaw), fz = Math.cos(yaw); // forward
		double lx = Math.cos(yaw), lz = Math.sin(yaw);  // left
		double dx, dz;
		switch (step.key) {
			case "w" -> { dx = fx; dz = fz; }
			case "s" -> { dx = -fx; dz = -fz; }
			case "a" -> { dx = lx; dz = lz; }
			default -> { dx = -lx; dz = -lz; } // d
		}
		moveOnX = Math.abs(dx) >= Math.abs(dz);
		moveSign = (int) Math.signum(moveOnX ? dx : dz);
		if (moveSign == 0) {
			moveSign = 1;
		}
		double pos = moveOnX ? player.getX() : player.getZ();
		moveTarget = Math.floor(pos) + 0.5 + moveSign * step.blocks;
		// give up if blocked (at least 2 blocks/second plus a couple of seconds' slack)
		moveDeadline = System.nanoTime() + (long) ((step.blocks / 2.0 + 2.0) * 1_000_000_000L);
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
		if (holdingKey != null) {
			KeyBinding.setKeyPressed(holdingKey, false);
			holdingKey = null;
		}
		moving = false;
		if (inventoryWork != null) {
			MinecraftClient client = MinecraftClient.getInstance();
			inventoryWork = null;
			if (openedInventory && client.player != null
					&& client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.InventoryScreen) {
				client.player.closeHandledScreen();
			}
			openedInventory = false;
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
			int run = 1;
			while (i + run < config.steps.size() && same(config.steps.get(i + run), s)) {
				run++;
			}
			String what = "refill".equals(s.action) ? "move " + (s.stacks > 0 ? s.stacks + " stack" + (s.stacks == 1 ? "" : "s") + " of " : "all ") + s.item + " to the hotbar"
					: "crafthay".equals(s.action) ? "craft " + (s.stacks > 0 ? s.stacks + " stack" + (s.stacks == 1 ? "" : "s") + " of " : "") + "hay bales into wheat"
							+ (s.wheatBelow > 0 ? " if under " + s.wheatBelow + " wheat" : "")
					: s.key == null ? "pause"
					: s.blocks > 0 ? "move " + s.key + " " + trim(s.blocks) + " blocks"
					: s.holdSeconds > 0 ? "hold " + s.key + " for " + trim(s.holdSeconds) + "s"
					: "press " + s.key;
			String number = run > 1 ? (i + 1) + "-" + (i + run) : String.valueOf(i + 1);
			source.sendFeedback(Text.literal(String.format(Locale.ROOT, "  %s. %s, then wait %ss%s",
					number, what, trim(s.waitSeconds), run > 1 ? "  (x" + run + ")" : "")).formatted(Formatting.WHITE));
			i += run - 1;
		}
		source.sendFeedback(Text.literal("  loop: " + (config.loop ? "on" : "off")
				+ "   /keytimer start [save] | stop | add | hold | move | wait | refill | crafthay | repeat | edit <n> | insert <n> | remove <n> | clear | loop | save | load | saves").formatted(Formatting.DARK_GRAY));
		return 1;
	}

	private interface StepAction {
		int run(com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> ctx, Step step);
	}

	/** Adds the "hold <key> <hold> <wait>", "add <key> <wait>" and "wait <seconds>" forms under {@code node}. */
	private static <T extends com.mojang.brigadier.builder.ArgumentBuilder<FabricClientCommandSource, T>> T stepForms(T node, StepAction action) {
		return node
				.then(literal("hold").then(argument("key", StringArgumentType.word())
						.then(argument("holdSeconds", DoubleArgumentType.doubleArg(0.05, 86400))
								.then(argument("waitSeconds", DoubleArgumentType.doubleArg(0, 86400)).executes(ctx -> {
									String key = StringArgumentType.getString(ctx, "key");
									if (parseKey(key) == null) {
										ctx.getSource().sendError(Text.literal("Unknown key \"" + key + "\""));
										return 0;
									}
									return action.run(ctx, new Step(key.toLowerCase(Locale.ROOT),
											DoubleArgumentType.getDouble(ctx, "holdSeconds"), DoubleArgumentType.getDouble(ctx, "waitSeconds")));
								})))))
				.then(literal("add").then(argument("key", StringArgumentType.word())
						.then(argument("waitSeconds", DoubleArgumentType.doubleArg(0, 86400)).executes(ctx -> {
							String key = StringArgumentType.getString(ctx, "key");
							if (parseKey(key) == null) {
								ctx.getSource().sendError(Text.literal("Unknown key \"" + key + "\""));
								return 0;
							}
							return action.run(ctx, new Step(key.toLowerCase(Locale.ROOT), 0, DoubleArgumentType.getDouble(ctx, "waitSeconds")));
						}))))
				.then(literal("wait").then(argument("seconds", DoubleArgumentType.doubleArg(0, 86400)).executes(ctx ->
						action.run(ctx, new Step(null, 0, DoubleArgumentType.getDouble(ctx, "seconds"))))))
				.then(moveForm(action))
				.then(refillForm(action))
				.then(craftHayForm(action));
	}

	/** "refill <item> <waitSeconds>": move every stack of the item from the inventory to the hotbar. */
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> refillForm(StepAction action) {
		return literal("refill").then(argument("item", StringArgumentType.word())
				.suggests((ctx, b) -> net.minecraft.command.CommandSource.suggestMatching(List.of("wheat", "hay_block", "carrot", "potato", "beetroot", "seagrass"), b))
				.then(argument("waitSeconds", DoubleArgumentType.doubleArg(0, 86400))
						.executes(ctx -> refill(ctx, action, 0))
						.then(argument("stacks", IntegerArgumentType.integer(1, 36))
								.executes(ctx -> refill(ctx, action, IntegerArgumentType.getInteger(ctx, "stacks"))))));
	}

	private static int refill(com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> ctx, StepAction action, int stacks) {
		String item = StringArgumentType.getString(ctx, "item").toLowerCase(Locale.ROOT);
		if (parseItem(item) == null) {
			ctx.getSource().sendError(Text.literal("Unknown item \"" + item + "\" (use names like wheat or hay_block)"));
			return 0;
		}
		return action.run(ctx, Step.inventory("refill", item, DoubleArgumentType.getDouble(ctx, "waitSeconds")).withStacks(stacks));
	}

	/** "crafthay <waitSeconds>": craft hay bales into wheat while there's room. */
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> craftHayForm(StepAction action) {
		return literal("crafthay").then(argument("waitSeconds", DoubleArgumentType.doubleArg(0, 86400))
				.executes(ctx -> action.run(ctx, Step.inventory("crafthay", null, DoubleArgumentType.getDouble(ctx, "waitSeconds"))))
				.then(argument("stacks", IntegerArgumentType.integer(1, 36)).executes(ctx ->
						action.run(ctx, Step.inventory("crafthay", null, DoubleArgumentType.getDouble(ctx, "waitSeconds"))
								.withStacks(IntegerArgumentType.getInteger(ctx, "stacks"))))
						.then(argument("whenWheatBelow", IntegerArgumentType.integer(1, 2304)).executes(ctx ->
								action.run(ctx, Step.inventory("crafthay", null, DoubleArgumentType.getDouble(ctx, "waitSeconds"))
										.withStacks(IntegerArgumentType.getInteger(ctx, "stacks"))
										.withWheatBelow(IntegerArgumentType.getInteger(ctx, "whenWheatBelow")))))));
	}

	/** "move <w|a|s|d> <blocks> <waitSeconds>": walk an exact number of blocks. */
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> moveForm(StepAction action) {
		return literal("move").then(argument("key", StringArgumentType.word())
				.suggests((ctx, b) -> net.minecraft.command.CommandSource.suggestMatching(List.of("w", "a", "s", "d"), b))
				.then(argument("blocks", DoubleArgumentType.doubleArg(0.5, 10000))
						.then(argument("waitSeconds", DoubleArgumentType.doubleArg(0, 86400)).executes(ctx -> {
							String key = StringArgumentType.getString(ctx, "key").toLowerCase(Locale.ROOT);
							if (!List.of("w", "a", "s", "d").contains(key)) {
								ctx.getSource().sendError(Text.literal("move only works with w, a, s or d"));
								return 0;
							}
							return action.run(ctx, Step.move(key, DoubleArgumentType.getDouble(ctx, "blocks"),
									DoubleArgumentType.getDouble(ctx, "waitSeconds")));
						}))));
	}

	private static String describe(Step s) {
		String what = "refill".equals(s.action) ? "move " + (s.stacks > 0 ? s.stacks + " stack" + (s.stacks == 1 ? "" : "s") + " of " : "all ") + s.item + " to the hotbar"
					: "crafthay".equals(s.action) ? "craft " + (s.stacks > 0 ? s.stacks + " stack" + (s.stacks == 1 ? "" : "s") + " of " : "") + "hay bales into wheat"
							+ (s.wheatBelow > 0 ? " if under " + s.wheatBelow + " wheat" : "")
					: s.key == null ? "pause"
				: s.blocks > 0 ? "move " + s.key + " " + trim(s.blocks) + " blocks"
				: s.holdSeconds > 0 ? "hold " + s.key + " for " + trim(s.holdSeconds) + "s"
				: "press " + s.key;
		return what + ", then wait " + trim(s.waitSeconds) + "s";
	}

	/** Replaces the current list with a save. */
	private static boolean loadSave(String name, FabricClientCommandSource source) {
		Saved saved = config.saves.get(name);
		if (saved == null) {
			source.sendError(Text.literal("There is no save called \"" + name + "\". See /keytimer saves"));
			return false;
		}
		stop(null);
		config.steps = copySteps(saved.steps);
		config.loop = saved.loop;
		save();
		source.sendFeedback(Text.literal("Loaded \"" + name + "\"").formatted(Formatting.GREEN));
		return true;
	}

	private static String duration(double seconds) {
		long s = Math.round(seconds);
		return s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : s + "s";
	}

	private static boolean same(Step a, Step b) {
		return java.util.Objects.equals(a.key, b.key) && a.holdSeconds == b.holdSeconds && a.waitSeconds == b.waitSeconds
				&& a.blocks == b.blocks && java.util.Objects.equals(a.action, b.action) && java.util.Objects.equals(a.item, b.item) && a.stacks == b.stacks && a.wheatBelow == b.wheatBelow;
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
		if (config.saves == null) {
			config.saves = new java.util.TreeMap<>();
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
