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
		/** look: the direction to face (pitch may be null to keep the current one). */
		Float lookYaw;
		Float lookPitch;
		/** goto: hold {@code key} until your X or Z ("x"/"z") reaches {@code gotoCoord}, or you get stuck. */
		String gotoAxis;
		Double gotoCoord;
		/** goto: walk no faster than this many blocks a second along the axis (null = full speed). */
		Double gotoSpeed;
		/** flick: keep nudging your pitch to a random angle between these, every flickEvery seconds (0 = stop). */
		Float flickMin;
		Float flickMax;
		Double flickEvery;
		/** Fast flick: how many angles it sweeps through (2 = just the two ends; default 3). */
		Integer flickSteps;
		/** clicker: keep clicking "left" or "right" this many times a second (0 = stop). */
		String clickButton;
		Double clickCps;

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

		static Step goTo(String key, String axis, double coord, double waitSeconds) {
			Step step = new Step(key, 0, waitSeconds);
			step.gotoAxis = axis;
			step.gotoCoord = coord;
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
			step.lookYaw = lookYaw;
			step.lookPitch = lookPitch;
			step.gotoAxis = gotoAxis;
			step.gotoCoord = gotoCoord;
			step.gotoSpeed = gotoSpeed;
			step.flickMin = flickMin;
			step.flickMax = flickMax;
			step.flickEvery = flickEvery;
			step.flickSteps = flickSteps;
			step.clickButton = clickButton;
			step.clickCps = clickCps;
			return step;
		}
	}

	/** A saved list of steps. */
	public static final class Saved {
		List<Step> steps = new ArrayList<>();
		boolean loop = true;
		/** Leave the server when you have fewer than leaveBelow of this item while running (null = off). */
		String leaveItem;
		int leaveBelow = 1;
	}

	public static final class Config {
		List<Step> steps = new ArrayList<>();
		/** Start over from the first step after the last one. */
		boolean loop = true;
		String leaveItem;
		int leaveBelow = 1;
		/** Named saves, e.g. "1" or "cowfarm". */
		java.util.Map<String, Saved> saves = new java.util.TreeMap<>();
		/** Auto-rejoin after a timeout (and 30 min after Safe Logout) while a route runs. */
		boolean rejoin = true;
		String rejoinServer = "mc.mvndicraft.net";
		/** Lobby: hotbar slot (1-9) with the server selector, and the menu slot to click (0 = top-left). */
		int lobbyHotbarSlot = 5;
		int lobbyMenuSlot = 11;
		int safeLogoutWaitMinutes = 30;
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
	private static List<InputUtil.Key> heldKey;
	/** Key being held down for a "hold" step, and when to let go. */
	private static List<InputUtil.Key> holdingKey;
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
	/** A "chest" step is running: a chest screen being open is expected, not a reason to stop. */
	private static boolean chestWork;
	/** Head flick: while on, your pitch wanders between flickLow and flickHigh. */
	private static boolean flicking;
	/** "eat" step: hold right-click with the item until none is left (or nothing gets eaten for a while). */
	private static java.util.function.Predicate<net.minecraft.item.ItemStack> eatMatches;
	private static int eatLastCount;
	private static long eatProgressAt;
	private static long eatWaitNanos;
	private static float flickLow, flickHigh, flickTarget;
	private static double flickEverySeconds;
	private static long nextFlickAt;
	private static int flickPhase;
	private static int flickPoints = 3;
	private static final java.util.Random RANDOM = new java.util.Random();
	/** Auto clicker: while on, presses the attack/use key clickRate times a second. */
	private static boolean clicking;
	private static boolean clickUse;
	private static double clickRate, clickAccum;

	private static boolean moving;
	private static boolean moveOnX;
	private static int moveSign;
	private static double moveTarget;
	/** goto steps also stop when you stop making progress (hit a wall), so they can't hang. */
	private static boolean gotoMode;
	private static double progressPos;
	private static long progressAt;
	/** Speed limit for the current goto step in blocks per tick (0 = none). */
	private static double gotoMaxPerTick;
	/** Speed cap by distance: where the goto started and when, so the average speed can't exceed the cap. */
	private static double gotoStartPos;
	private static long gotoStartAt;
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
		ClientTickEvents.END_CLIENT_TICK.register(Rejoin::tick);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(literal("keytimer")
				.executes(ctx -> list(ctx.getSource()))
				.then(literal("add").then(argument("key", StringArgumentType.string())
						.then(argument("waitSeconds", DoubleArgumentType.doubleArg(0, 86400)).executes(ctx -> {
							String key = StringArgumentType.getString(ctx, "key");
							if (parseKeys(key) == null) {
								ctx.getSource().sendError(Text.literal("Unknown key \"" + key
										+ "\". Use things like 1-9, a-z, space, left (mouse), right (mouse), f, q, e."));
								return 0;
							}
							config.steps.add(new Step(key.toLowerCase(Locale.ROOT), 0, DoubleArgumentType.getDouble(ctx, "waitSeconds")));
							save();
							return list(ctx.getSource());
						}))))
				.then(literal("hold").then(argument("key", StringArgumentType.string())
						.then(argument("holdSeconds", DoubleArgumentType.doubleArg(0.05, 86400))
								.then(argument("waitSeconds", DoubleArgumentType.doubleArg(0, 86400)).executes(ctx -> {
									String key = StringArgumentType.getString(ctx, "key");
									if (parseKeys(key) == null) {
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
				.then(lookForm((ctx, step) -> {
					config.steps.add(step);
					save();
					return list(ctx.getSource());
				}))
				.then(dropForm((ctx, step) -> {
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
				.then(literal("leavewhenout")
						.then(literal("off").executes(ctx -> {
							config.leaveItem = null;
							save();
							ctx.getSource().sendFeedback(Text.literal("Won't leave the server when items run out").formatted(Formatting.YELLOW));
							return 1;
						}))
						.then(literal("named").then(argument("name", StringArgumentType.string())
								.executes(ctx -> setLeave(ctx.getSource(), "name:" + StringArgumentType.getString(ctx, "name").trim(), 1))
								.then(argument("below", IntegerArgumentType.integer(1, 100000))
										.executes(ctx -> setLeave(ctx.getSource(), "name:" + StringArgumentType.getString(ctx, "name").trim(),
												IntegerArgumentType.getInteger(ctx, "below"))))))
						.then(argument("item", StringArgumentType.string())
								.suggests((ctx, b) -> net.minecraft.command.CommandSource.suggestMatching(List.of("hay_block", "wheat", "named", "off"), b))
								.executes(ctx -> setLeave(ctx.getSource(), StringArgumentType.getString(ctx, "item").toLowerCase(Locale.ROOT), 1))
								.then(argument("below", IntegerArgumentType.integer(1, 100000))
										.executes(ctx -> setLeave(ctx.getSource(), StringArgumentType.getString(ctx, "item").toLowerCase(Locale.ROOT),
												IntegerArgumentType.getInteger(ctx, "below"))))))
				.then(literal("loop").then(argument("on", BoolArgumentType.bool()).executes(ctx -> {
					config.loop = BoolArgumentType.getBool(ctx, "on");
					save();
					return list(ctx.getSource());
				})))
				.then(literal("start").executes(ctx -> {
					start(ctx.getSource()::sendFeedback);
					return 1;
				}).then(argument("save", StringArgumentType.word()).suggests(saveNames()).executes(ctx -> {
					String name = StringArgumentType.getString(ctx, "save");
					if (!config.saves.containsKey(name) && name.matches("\\d{1,4}")) {
						// not a save: a step number in the current list
						return start(ctx.getSource()::sendFeedback, Integer.parseInt(name)) ? 1 : 0;
					}
					if (!loadSave(name, ctx.getSource())) {
						return 0;
					}
					start(ctx.getSource()::sendFeedback);
					return 1;
				}).then(argument("step", IntegerArgumentType.integer(1)).executes(ctx -> {
					if (!loadSave(StringArgumentType.getString(ctx, "save"), ctx.getSource())) {
						return 0;
					}
					return start(ctx.getSource()::sendFeedback, IntegerArgumentType.getInteger(ctx, "step")) ? 1 : 0;
				}))))
				.then(literal("startat").then(argument("step", IntegerArgumentType.integer(1)).executes(ctx ->
						start(ctx.getSource()::sendFeedback, IntegerArgumentType.getInteger(ctx, "step")) ? 1 : 0)))
				.then(literal("save").then(argument("name", StringArgumentType.word()).suggests(saveNames()).executes(ctx -> {
					String name = StringArgumentType.getString(ctx, "name");
					if (config.steps.isEmpty()) {
						ctx.getSource().sendError(Text.literal("There are no steps to save"));
						return 0;
					}
					Saved saved = new Saved();
					saved.steps = copySteps(config.steps);
					saved.loop = config.loop;
					saved.leaveItem = config.leaveItem;
					saved.leaveBelow = config.leaveBelow;
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
					Rejoin.cancel();
					stop(ctx.getSource()::sendFeedback);
					return 1;
				}))
				.then(literal("resume").executes(ctx -> {
					if (!Rejoin.forceResume()) {
						ctx.getSource().sendError(Text.literal("Nothing to resume"));
						return 0;
					}
					ctx.getSource().sendFeedback(Text.literal("Carrying on in 5 seconds").formatted(Formatting.GREEN));
					return 1;
				}))
				.then(literal("rejoin")
						.executes(ctx -> Rejoin.status(ctx.getSource()::sendFeedback))
						.then(literal("on").executes(ctx -> { config.rejoin = true; save(); return Rejoin.status(ctx.getSource()::sendFeedback); }))
						.then(literal("off").executes(ctx -> { config.rejoin = false; Rejoin.cancel(); save(); return Rejoin.status(ctx.getSource()::sendFeedback); }))
						.then(literal("cancel").executes(ctx -> { Rejoin.cancel(); return Rejoin.status(ctx.getSource()::sendFeedback); }))
						.then(literal("server").then(argument("address", StringArgumentType.word()).executes(ctx -> {
							config.rejoinServer = StringArgumentType.getString(ctx, "address");
							save();
							return Rejoin.status(ctx.getSource()::sendFeedback);
						})))
						.then(literal("hotbar").then(argument("slot", IntegerArgumentType.integer(1, 9)).executes(ctx -> {
							config.lobbyHotbarSlot = IntegerArgumentType.getInteger(ctx, "slot");
							save();
							return Rejoin.status(ctx.getSource()::sendFeedback);
						})))
						.then(literal("menuslot").then(argument("slot", IntegerArgumentType.integer(0, 89)).executes(ctx -> {
							config.lobbyMenuSlot = IntegerArgumentType.getInteger(ctx, "slot");
							save();
							return Rejoin.status(ctx.getSource()::sendFeedback);
						})))
						.then(literal("wait").then(argument("minutes", IntegerArgumentType.integer(1, 600)).executes(ctx -> {
							config.safeLogoutWaitMinutes = IntegerArgumentType.getInteger(ctx, "minutes");
							save();
							return Rejoin.status(ctx.getSource()::sendFeedback);
						}))))));
	}

	// ---------------------------------------------------------------- running

	private static void tick(MinecraftClient client) {
		while (startStopKey.wasPressed()) {
			if (running || Rejoin.isActive()) {
				Rejoin.cancel();
				stop(KeyTimer::actionBar);
			} else {
				start(KeyTimer::actionBar);
			}
		}
		if (heldKey != null) {
			press(heldKey, false);
			heldKey = null;
		}
		if (!running) {
			return;
		}
		if (client.player == null) {
			Rejoin.onLostWorld(client); // timeout -> suspend and rejoin; otherwise stop
			return;
		}
		long now = System.nanoTime();
		checkLeave(client, now);
		if (!running) {
			return;
		}
		if (flicking && client.currentScreen == null) {
			if (now >= nextFlickAt - 5_000_000L) {
				if (flickEverySeconds <= 0.1) {
					// fast: sweep up and down through flickPoints evenly spaced angles, one per tick
					// (3 points: 40, 44, 48, 44, ...), so the middle angles get clicked too
					int last = flickPoints - 1;
					int cycle = 2 * last;
					int k = flickPhase <= last ? flickPhase : cycle - flickPhase;
					flickTarget = flickLow + (flickHigh - flickLow) * k / (float) last;
					flickPhase = (flickPhase + 1) % cycle;
					nextFlickAt = now + (long) (flickEverySeconds * 1_000_000_000L);
				} else {
					flickTarget = flickLow + RANDOM.nextFloat() * (flickHigh - flickLow);
					// not on an exact beat: somewhere between 0.6x and 1.4x the interval
					nextFlickAt = now + (long) (flickEverySeconds * (0.6 + RANDOM.nextDouble() * 0.8) * 1_000_000_000L);
				}
				client.player.setPitch(flickTarget); // a flick: snap straight there
			}
		}
		if (clicking && client.currentScreen == null) {
			clickAccum += clickRate / 20.0;
			if (clickUse && client.player.isUsingItem()) {
				// e.g. started drinking a milk bucket: cancel it so the next click works straight away
				client.interactionManager.stopUsingItem(client.player);
			}
			// only click when looking at a block or an animal: a click on air would drink milk / eat / throw
			boolean target = client.crosshairTarget != null && client.crosshairTarget.getType() != net.minecraft.util.hit.HitResult.Type.MISS;
			KeyBinding binding = clickUse ? client.options.useKey : client.options.attackKey;
			InputUtil.Key key = InputUtil.fromTranslationKey(binding.getBoundKeyTranslationKey());
			while (clickAccum >= 1) {
				if (target) {
					KeyBinding.onKeyPressed(key); // one click, handled next tick like a real one
				}
				clickAccum -= 1;
			}
		}
		if (inventoryWork != null) {
			if (!chestWork && client.currentScreen != null && !(client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.InventoryScreen)) {
				finishInventory(client, now); // something else opened; skip the rest
				return;
			}
			net.minecraft.util.math.Vec3d v = client.player.getVelocity();
			if (!chestWork && (v.x * v.x + v.z * v.z > 0.0004 || !client.player.isOnGround())) {
				return; // servers often ignore inventory clicks while you're moving: wait until you've stopped
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
		if (eatMatches != null) {
			tickEat(client, now);
			return;
		}
		if (holdingKey != null && moving) {
			ClientPlayerEntity player = client.player;
			double pos = moveOnX ? player.getX() : player.getZ();
			double vel = Math.max(0, (moveOnX ? player.getVelocity().x : player.getVelocity().z) * moveSign);
			double error = (moveTarget - pos) * moveSign - vel * SLIDE_FACTOR; // where we'd stop if we let go now
			boolean stuck = false;
			if (gotoMode) {
				if (Math.abs(pos - progressPos) > 0.25) {
					progressPos = pos;
					progressAt = now;
				} else if (now - progressAt > 1_500_000_000L) {
					stuck = true; // no progress for 1.5 seconds: against a wall, so this step is done
				}
			}
			// keep going while one more tick (moving about vel / 0.546) gets the stop closer to the target
			if (error > vel / 0.546 / 2 && now < moveDeadline && !stuck) {
				// speed limit: let go whenever we're further along than the capped speed allows
				boolean ahead = false;
				if (gotoMode && gotoMaxPerTick > 0) {
					double allowed = gotoMaxPerTick * 20 * (now - gotoStartAt) / 1e9 + 0.3; // small head start to get moving
					double travelled = (pos - gotoStartPos) * moveSign;
					ahead = travelled > allowed;
					if (ahead) {
						progressAt = now; // waiting on purpose is not being stuck
					}
				}
				press(holdingKey, !ahead);
				return;
			}
			press(holdingKey, false);
			holdingKey = null;
			moving = false;
			nextStepAt = now + moveWaitNanos;
		}
		if (holdingKey != null) {
			if (now < holdUntil) {
				press(holdingKey, true); // keep it down even if a real key event reset it
				return;
			}
			press(holdingKey, false);
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
		currentStep = stepIndex;
		if ("look".equals(step.action)) {
			stepIndex++;
			if (step.lookYaw != null) {
				client.player.setYaw(step.lookYaw);
				client.player.setHeadYaw(step.lookYaw);
			}
			if (step.lookPitch != null) {
				client.player.setPitch(step.lookPitch);
			}
			nextStepAt = now + (long) (step.waitSeconds * 1_000_000_000L);
			return;
		}
		if ("eat".equals(step.action)) {
			stepIndex++;
			eatMatches = itemMatcher(step.item);
			if (eatMatches == null) {
				nextStepAt = now + (long) (step.waitSeconds * 1_000_000_000L);
				return;
			}
			eatLastCount = Integer.MAX_VALUE;
			eatProgressAt = now;
			eatWaitNanos = (long) (step.waitSeconds * 1_000_000_000L);
			return;
		}
		if ("slot".equals(step.action)) {
			stepIndex++;
			net.minecraft.entity.player.PlayerInventory inv = client.player.getInventory();
			int target = Math.max(0, Math.min(8, step.stacks - 1));
			if (findSlot(inv, step.item, target, target + 1) < 0) {
				// not there yet: swap the first match (hotbar or inventory) into that slot, like pressing 1-9 over it
				int from = findSlot(inv, step.item, 0, 36);
				if (from >= 0) {
					int screenSlot = from < 9 ? 36 + from : from;
					client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId, screenSlot, target,
							net.minecraft.screen.slot.SlotActionType.SWAP, client.player);
				} else {
					actionBar(Text.literal("Key Timer: none of " + step.item.replace("|", ", ") + " in your inventory").formatted(Formatting.RED));
				}
			}
			KeyBinding binding = client.options.hotbarKeys[target];
			KeyBinding.onKeyPressed(InputUtil.fromTranslationKey(binding.getBoundKeyTranslationKey())); // move the selection there
			nextStepAt = now + (long) (step.waitSeconds * 1_000_000_000L);
			return;
		}
		if ("select".equals(step.action)) {
			stepIndex++;
			net.minecraft.entity.player.PlayerInventory inv = client.player.getInventory();
			int slot = findSlot(inv, step.item, 0, 9);
			int fromInventory = slot < 0 ? findSlot(inv, step.item, 9, 36) : -1;
			if (slot >= 0) {
				KeyBinding binding = client.options.hotbarKeys[slot];
				KeyBinding.onKeyPressed(InputUtil.fromTranslationKey(binding.getBoundKeyTranslationKey())); // like pressing 1-9
			} else if (fromInventory >= 0) {
				// not in the hotbar: swap it from the inventory into the slot you're holding (like pressing 1-9 over it)
				client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId, fromInventory,
						inv.getSelectedSlot(), net.minecraft.screen.slot.SlotActionType.SWAP, client.player);
			} else {
				actionBar(Text.literal("Key Timer: none of " + step.item.replace("|", ", ") + " in your inventory").formatted(Formatting.RED));
			}
			nextStepAt = now + (long) (step.waitSeconds * 1_000_000_000L);
			return;
		}
		if ("clicker".equals(step.action)) {
			stepIndex++;
			clicking = step.clickCps != null && step.clickCps > 0;
			clickUse = !"left".equals(step.clickButton);
			clickRate = clicking ? step.clickCps : 0;
			clickAccum = 0;
			nextStepAt = now + (long) (step.waitSeconds * 1_000_000_000L);
			return;
		}
		if ("flick".equals(step.action)) {
			stepIndex++;
			flicking = step.flickEvery != null && step.flickEvery > 0;
			if (flicking) {
				flickLow = Math.min(step.flickMin, step.flickMax);
				flickHigh = Math.max(step.flickMin, step.flickMax);
				flickEverySeconds = step.flickEvery;
				flickPoints = step.flickSteps != null ? Math.max(2, Math.min(8, step.flickSteps)) : 3;
				flickPhase = 0;
				flickTarget = client.player.getPitch();
				nextFlickAt = now;
			}
			nextStepAt = now + (long) (step.waitSeconds * 1_000_000_000L);
			return;
		}
		if (step.action != null) {
			stepIndex++;
			startInventory(client, step);
			return;
		}
		List<InputUtil.Key> key = step.key == null ? null : parseKeys(step.key);
		long start = now;
		if (key != null) {
			// same as a real key press: counts as "pressed" once and is held down
			press(key, true);
			for (InputUtil.Key k : key) {
				KeyBinding.onKeyPressed(k);
			}
			if (step.blocks > 0 || step.gotoAxis != null) {
				if (step.gotoAxis != null) {
					startGoto(client.player, step, now);
				} else {
					gotoMode = false;
					startMove(client.player, step);
				}
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
		if ("chest".equals(step.action) || "deposit".equals(step.action)) {
			startChest(client, step);
			return;
		}
		openedInventory = false;
		if (client.currentScreen == null) {
			client.setScreen(new net.minecraft.client.gui.screen.ingame.InventoryScreen(player));
			openedInventory = true;
		}
		inventoryDelay = 4; // let the screen open (and the server catch up) before the first click
		if ("refill".equals(step.action)) {
			java.util.function.Predicate<net.minecraft.item.ItemStack> matches = itemMatcher(step.item);
			int[] guard = {0};
			int limit = step.stacks > 0 ? step.stacks : 40;
			inventoryWork = () -> matches != null && guard[0]++ < limit && refillOnce(client, matches);
		} else if ("drop".equals(step.action)) {
			java.util.function.Predicate<net.minecraft.item.ItemStack> matches = itemMatcher(step.item);
			int[] guard = {0};
			inventoryWork = () -> matches != null && guard[0]++ < 40 && dropOnce(client, matches);
		} else if ("swapin".equals(step.action)) {
			java.util.function.Predicate<net.minecraft.item.ItemStack> matches = itemMatcher(step.item);
			int[] guard = {0};
			inventoryWork = () -> matches != null && guard[0]++ < 9 && swapInOnce(client, matches);
		} else if ("stash".equals(step.action)) {
			java.util.function.Predicate<net.minecraft.item.ItemStack> matches = itemMatcher(step.item);
			int[] slot = {0};
			inventoryWork = () -> matches != null && stashOnce(client, matches, slot);
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
				&& (client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.InventoryScreen
						|| (chestWork && client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?>))) {
			client.player.closeHandledScreen();
		}
		openedInventory = false;
		chestWork = false;
		nextStepAt = now + inventoryWaitNanos;
	}

	/**
	 * Right-clicks the block you're looking at (a chest, barrel, ...), then shift-clicks matching
	 * stacks out of it until you carry {@code stacks} stacks' worth (default 9), and closes it.
	 */
	/**
	 * First hotbar slot holding any of the items in {@code spec} ("bucket|Sheep's Milk|Cow's Milk"):
	 * each part is an item id like bucket, or else the name shown on the item. Earlier parts win.
	 */
	private static int findSlot(net.minecraft.entity.player.PlayerInventory inv, String spec, int from, int to) {
		for (String part : spec.split("\\|")) {
			String p = part.trim();
			if (p.isEmpty()) {
				continue;
			}
			java.util.function.Predicate<net.minecraft.item.ItemStack> matches = p.startsWith("name:") ? itemMatcher(p)
					: parseItem(p.toLowerCase(Locale.ROOT)) != null ? itemMatcher(p.toLowerCase(Locale.ROOT)) : itemMatcher("name:" + p);
			for (int i = from; i < to; i++) {
				if (!inv.getStack(i).isEmpty() && matches.test(inv.getStack(i))) {
					return i;
				}
			}
		}
		return -1;
	}

	private static void startChest(MinecraftClient client, Step step) {
		ClientPlayerEntity player = client.player;
		java.util.function.Predicate<net.minecraft.item.ItemStack> matches = itemMatcher(step.item);
		openedInventory = false;
		if (matches == null || !(client.crosshairTarget instanceof net.minecraft.util.hit.BlockHitResult hit)
				|| hit.getType() != net.minecraft.util.hit.HitResult.Type.BLOCK) {
			actionBar(Text.literal("Key Timer: not looking at a chest, skipped that step").formatted(Formatting.RED));
			nextStepAt = System.nanoTime() + inventoryWaitNanos;
			return;
		}
		client.interactionManager.interactBlock(player, net.minecraft.util.Hand.MAIN_HAND, hit);
		chestWork = true;
		int want = (step.stacks > 0 ? step.stacks : 9) * 64;
		int[] state = {0, 0, 0, 0, 0}; // [ticks waited for the screen, next slot, settled, stacks taken, rescans]
		inventoryDelay = 0;
		boolean deposit = "deposit".equals(step.action);
		inventoryWork = () -> deposit ? depositStep(client, matches, state) : chestStep(client, matches, want, state);
	}

	/** "deposit": shift-clicks every matching stack from your inventory into the open chest. */
	private static boolean depositStep(MinecraftClient client, java.util.function.Predicate<net.minecraft.item.ItemStack> matches, int[] state) {
		if (!(client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?>)
				|| client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.InventoryScreen) {
			if (state[0]++ < 40) {
				return true; // wait up to 2 seconds for the chest to open
			}
			actionBar(Text.literal("Key Timer: the chest didn't open").formatted(Formatting.RED));
			return false;
		}
		openedInventory = true; // close it when done
		if (state[2] == 0) {
			state[2] = 1;
			inventoryDelay = 3;
			return true;
		}
		net.minecraft.entity.player.PlayerInventory inv = client.player.getInventory();
		net.minecraft.screen.ScreenHandler handler = client.player.currentScreenHandler;
		while (state[1] < handler.slots.size()) {
			net.minecraft.screen.slot.Slot slot = handler.slots.get(state[1]++);
			if (slot.inventory != inv || !slot.hasStack() || !matches.test(slot.getStack())) {
				continue;
			}
			client.interactionManager.clickSlot(handler.syncId, slot.id, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, client.player);
			inventoryDelay = 1;
			return true;
		}
		return false;
	}

	private static boolean chestStep(MinecraftClient client, java.util.function.Predicate<net.minecraft.item.ItemStack> matches, int want, int[] state) {
		if (!(client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?>)
				|| client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.InventoryScreen) {
			if (state[0]++ < 40) {
				return true; // wait up to 2 seconds for the chest to open
			}
			actionBar(Text.literal("Key Timer: the chest didn't open").formatted(Formatting.RED));
			return false;
		}
		openedInventory = true; // close it when done
		net.minecraft.entity.player.PlayerInventory inv = client.player.getInventory();
		int have = 0;
		for (int i = 0; i < 36; i++) {
			if (matches.test(inv.getStack(i))) {
				have += inv.getStack(i).getCount();
			}
		}
		if (have >= want) {
			return false;
		}
		net.minecraft.screen.ScreenHandler handler = client.player.currentScreenHandler;
		if (state[2] == 0) {
			state[2] = 1; // first tick with the screen open: give its contents a moment to arrive
			inventoryDelay = 5;
			return true;
		}
		while (state[1] < handler.slots.size()) {
			net.minecraft.screen.slot.Slot slot = handler.slots.get(state[1]++);
			if (slot.inventory == inv || !slot.hasStack() || !matches.test(slot.getStack())) {
				continue;
			}
			client.interactionManager.clickSlot(handler.syncId, slot.id, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, client.player);
			state[3]++;
			inventoryDelay = 2; // one stack every few ticks so the server keeps up
			return true;
		}
		// went through the whole chest: if nothing was taken yet, the contents may not have arrived; look again
		if (state[3] == 0 && state[4]++ < 30) {
			state[1] = 0;
			inventoryDelay = 2;
			return true;
		}
		return false;
	}

	private static void click(MinecraftClient client, int slotId, int button, net.minecraft.screen.slot.SlotActionType type) {
		ClientPlayerEntity player = client.player;
		client.interactionManager.clickSlot(player.playerScreenHandler.syncId, slotId, button, type, player);
	}

	/**
	 * Shift-clicks one stack of {@code item} from the main inventory (screen slots 9-35) into the
	 * hotbar, if the hotbar has room for it. Returns false when there's nothing more to move.
	 */
	private static boolean refillOnce(MinecraftClient client, java.util.function.Predicate<net.minecraft.item.ItemStack> matches) {
		net.minecraft.entity.player.PlayerInventory inv = client.player.getInventory();
		for (int i = 9; i < 36; i++) {
			net.minecraft.item.ItemStack stack = inv.getStack(i);
			if (stack.isEmpty() || !matches.test(stack) || !hotbarHasRoomFor(inv, stack)) {
				continue;
			}
			click(client, i, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE); // inventory index 9-35 = screen slot 9-35
			return true;
		}
		return false;
	}

	/** Throws one whole stack matching {@code matches} (hotbar or main inventory). False when none are left. */
	private static boolean dropOnce(MinecraftClient client, java.util.function.Predicate<net.minecraft.item.ItemStack> matches) {
		net.minecraft.entity.player.PlayerInventory inv = client.player.getInventory();
		for (int i = 0; i < 36; i++) {
			net.minecraft.item.ItemStack stack = inv.getStack(i);
			if (!stack.isEmpty() && matches.test(stack)) {
				int screenSlot = i < 9 ? 36 + i : i;
				click(client, screenSlot, 1, net.minecraft.screen.slot.SlotActionType.THROW); // like Ctrl+Q over the slot
				return true;
			}
		}
		return false;
	}

	/** Shift-clicks the next matching hotbar stack into the main inventory. False once every hotbar slot was checked. */
	/**
	 * Fills the hotbar with the item even when the inventory is full: swaps a stack of it into the next
	 * hotbar slot that's empty or holds something else (that item goes where the stack was).
	 */
	private static boolean swapInOnce(MinecraftClient client, java.util.function.Predicate<net.minecraft.item.ItemStack> matches) {
		net.minecraft.entity.player.PlayerInventory inv = client.player.getInventory();
		int target = -1;
		for (int h = 0; h < 9 && target < 0; h++) {
			if (inv.getStack(h).isEmpty()) {
				target = h;
			}
		}
		for (int h = 0; h < 9 && target < 0; h++) {
			if (!matches.test(inv.getStack(h))) {
				target = h;
			}
		}
		if (target < 0) {
			return false; // the whole hotbar already holds it
		}
		for (int i = 9; i < 36; i++) {
			if (matches.test(inv.getStack(i))) {
				click(client, i, target, net.minecraft.screen.slot.SlotActionType.SWAP); // like pressing 1-9 over it
				return true;
			}
		}
		return false; // none left in the inventory
	}

	private static boolean stashOnce(MinecraftClient client, java.util.function.Predicate<net.minecraft.item.ItemStack> matches, int[] slot) {
		net.minecraft.entity.player.PlayerInventory inv = client.player.getInventory();
		while (slot[0] < 9) {
			int i = slot[0]++;
			net.minecraft.item.ItemStack stack = inv.getStack(i);
			if (!stack.isEmpty() && matches.test(stack)) {
				click(client, 36 + i, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE); // hotbar -> main inventory
				return true;
			}
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

	/**
	 * What a refill step looks for: "wheat" / "minecraft:wheat" matches the item type;
	 * "name:Rennet" matches the name shown on the item (any case, underscores for spaces),
	 * for custom server items that are a normal item with a custom name.
	 */
	private static java.util.function.Predicate<net.minecraft.item.ItemStack> itemMatcher(String spec) {
		if (spec == null) {
			return null;
		}
		if (spec.contains("|")) {
			// any of several items: "name:Clothbound Cheddar|Pecorino Romano" (plain parts are item ids or names)
			java.util.List<java.util.function.Predicate<net.minecraft.item.ItemStack>> parts = new ArrayList<>();
			for (String part : spec.split("\\|")) {
				String q = part.trim();
				if (q.isEmpty()) {
					continue;
				}
				java.util.function.Predicate<net.minecraft.item.ItemStack> m = q.startsWith("name:") ? itemMatcher(q)
						: parseItem(q.toLowerCase(Locale.ROOT)) != null ? itemMatcher(q.toLowerCase(Locale.ROOT)) : itemMatcher("name:" + q);
				if (m != null) {
					parts.add(m);
				}
			}
			return parts.isEmpty() ? null : stack -> !stack.isEmpty() && parts.stream().anyMatch(m -> m.test(stack));
		}
		if (spec.startsWith("name:")) {
			String wanted = normalizeName(spec.substring(5));
			return stack -> normalizeName(stack.getName().getString()).equals(wanted);
		}
		net.minecraft.item.Item item = parseItem(spec);
		return item == null ? null : stack -> stack.isOf(item);
	}

	private static String normalizeName(String name) {
		return name.replace('_', ' ').trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
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
		double dx = 0, dz = 0;
		for (String part : step.key.split("\\+")) {
			switch (part.trim()) {
				case "w" -> { dx += fx; dz += fz; }
				case "s" -> { dx -= fx; dz -= fz; }
				case "a" -> { dx += lx; dz += lz; }
				case "d" -> { dx -= lx; dz -= lz; }
				default -> { }
			}
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

	private static void tickEat(MinecraftClient client, long now) {
		net.minecraft.entity.player.PlayerInventory inv = client.player.getInventory();
		KeyBinding use = client.options.useKey;
		int count = 0;
		int first = -1;
		for (int i = 0; i < 36; i++) {
			if (eatMatches.test(inv.getStack(i))) {
				count += inv.getStack(i).getCount();
				if (first < 0 || (first >= 9 && i < 9)) {
					first = i;
				}
			}
		}
		if (count < eatLastCount) {
			eatLastCount = count;
			eatProgressAt = now;
		}
		boolean stuck = now - eatProgressAt > 15_000_000_000L; // nothing eaten for 15 s (full, or can't eat it)
		if (count == 0 || stuck || client.currentScreen != null) {
			if (stuck && count > 0) {
				actionBar(Text.literal("Key Timer: can't eat any more right now, moving on").formatted(Formatting.YELLOW));
			}
			use.setPressed(false);
			eatMatches = null;
			nextStepAt = now + eatWaitNanos;
			return;
		}
		if (!eatMatches.test(client.player.getMainHandStack())) {
			use.setPressed(false);
			if (first < 9) {
				KeyBinding.onKeyPressed(InputUtil.fromTranslationKey(client.options.hotbarKeys[first].getBoundKeyTranslationKey()));
			} else {
				client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId, first, inv.getSelectedSlot(),
						net.minecraft.screen.slot.SlotActionType.SWAP, client.player);
			}
			return;
		}
		use.setPressed(true); // keep eating
	}

	/** Aims for an exact X or Z coordinate (F3 numbers), whichever way the keys take you there. */
	private static void startGoto(ClientPlayerEntity player, Step step, long now) {
		moveOnX = "x".equals(step.gotoAxis);
		double pos = moveOnX ? player.getX() : player.getZ();
		moveTarget = step.gotoCoord;
		moveSign = moveTarget >= pos ? 1 : -1;
		gotoMode = true;
		gotoMaxPerTick = step.gotoSpeed != null && step.gotoSpeed > 0 ? step.gotoSpeed / 20.0 : 0;
		gotoStartPos = pos;
		gotoStartAt = now;
		progressPos = pos;
		progressAt = now;
		// generous: even walking slowly along a wall (about 1.5 blocks/second) gets there in time
		double slowest = gotoMaxPerTick > 0 ? Math.min(0.8, gotoMaxPerTick * 20 * 0.6) : 0.8;
		moveDeadline = now + (long) ((Math.abs(moveTarget - pos) / slowest + 10.0) * 1_000_000_000L);
	}

	private static int setLeave(FabricClientCommandSource source, String item, int below) {
		if (itemMatcher(item) == null) {
			source.sendError(Text.literal("Unknown item \"" + item + "\". Use an id like hay_block, or leavewhenout named <name>"));
			return 0;
		}
		config.leaveItem = item;
		config.leaveBelow = below;
		save();
		source.sendFeedback(Text.literal("While running, you'll leave the server when you have " + (below <= 1 ? "no " : "fewer than " + below + " ")
				+ (item.startsWith("name:") ? item.substring(5) : item) + " left. Remember /keytimer save <name> to keep this in a save.").formatted(Formatting.GREEN));
		return 1;
	}

	/**
	 * While a route runs, never drink milk: a right-click that misses a vat would otherwise start
	 * drinking and block every click for over a second. Cancelled the same tick it starts.
	 */
	/** Out-of-items check: only between steps with no screen open, and only once it has lasted 2 seconds. */
	private static long outSince;

	private static void checkLeave(MinecraftClient client, long now) {
		if (config.leaveItem == null || inventoryWork != null || client.currentScreen != null) {
			outSince = 0;
			return;
		}
		java.util.function.Predicate<net.minecraft.item.ItemStack> matches = itemMatcher(config.leaveItem);
		if (matches == null) {
			return;
		}
		net.minecraft.entity.player.PlayerInventory inv = client.player.getInventory();
		int count = 0;
		for (int i = 0; i < inv.size(); i++) {
			if (matches.test(inv.getStack(i))) {
				count += inv.getStack(i).getCount();
			}
		}
		if (matches.test(client.player.currentScreenHandler.getCursorStack())) {
			count += client.player.currentScreenHandler.getCursorStack().getCount();
		}
		if (count >= Math.max(1, config.leaveBelow)) {
			outSince = 0;
			return;
		}
		if (outSince == 0) {
			outSince = now;
			return;
		}
		if (now - outSince < 2_000_000_000L) {
			return;
		}
		outSince = 0;
		String what = config.leaveItem.startsWith("name:") ? config.leaveItem.substring(5) : config.leaveItem;
		stop(null);
		if (client.getNetworkHandler() != null) {
			client.getNetworkHandler().getConnection().disconnect(Text.literal("Key Timer: ran out of " + what + ", so you left the server."));
		}
	}

	private static void start(java.util.function.Consumer<Text> feedback) {
		start(feedback, 1);
	}

	/** Starts at step {@code firstStep} (1-based); the loop still goes back to step 1 afterwards. */
	private static boolean start(java.util.function.Consumer<Text> feedback, int firstStep) {
		if (config.steps.isEmpty()) {
			feedback.accept(Text.literal("Key Timer has no steps. Add one with /keytimer add <key> <seconds>").formatted(Formatting.RED));
			return false;
		}
		if (firstStep < 1 || firstStep > config.steps.size()) {
			feedback.accept(Text.literal("There is no step " + firstStep + " (the list has " + config.steps.size() + ")").formatted(Formatting.RED));
			return false;
		}
		stop(null);
		running = true;
		stepIndex = firstStep - 1;
		nextStepAt = 0; // first key right away
		feedback.accept(Text.literal("Key Timer started" + (firstStep > 1 ? " at step " + firstStep : "") + " (" + config.steps.size() + " steps"
				+ (config.loop ? ", looping" : "") + ")").formatted(Formatting.GREEN));
		return true;
	}

	// ---------------------------------------------------------------- auto-rejoin support (see Rejoin)

	/** Index of the step that is currently running (resumed from after a rejoin). */
	private static int currentStep;
	private static boolean suspendedClicking, suspendedFlicking;
	private static int suspendedIndex = -1;

	static Config config() {
		return config;
	}

	static boolean isRunning() {
		return running;
	}

	/** Lets go of everything but keeps the route and the step it was on, to resume after rejoining. */
	static void suspend() {
		suspendedIndex = currentStep;
		suspendedClicking = clicking;
		suspendedFlicking = flicking;
		stop(null);
	}

	static boolean isSuspended() {
		return suspendedIndex >= 0;
	}

	static void cancelSuspend() {
		suspendedIndex = -1;
	}

	/** Carries on from the step that was running when the connection dropped. */
	static void resume() {
		if (suspendedIndex < 0 || config.steps.isEmpty()) {
			return;
		}
		int index = Math.min(suspendedIndex, config.steps.size() - 1);
		suspendedIndex = -1;
		running = true;
		stepIndex = index;
		nextStepAt = 0;
		clicking = suspendedClicking;
		flicking = suspendedFlicking;
		actionBar(Text.literal("Key Timer resumed at step " + (index + 1)).formatted(Formatting.GREEN));
	}

	static void stopForGood() {
		suspendedIndex = -1;
		stop(null);
	}

	/** Called by Safe Logout (if installed) just before it disconnects you. */
	public static void onSafeLogout() {
		Rejoin.onSafeLogout();
	}

	static void logInfo(String message) {
		org.slf4j.LoggerFactory.getLogger("keytimer").info(message);
	}

	static void say(Text text) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player != null) {
			client.player.sendMessage(text, false);
		}
	}

	private static void stop(java.util.function.Consumer<Text> feedback) {
		boolean was = running;
		running = false;
		if (heldKey != null) {
			press(heldKey, false);
			heldKey = null;
		}
		if (holdingKey != null) {
			press(holdingKey, false);
			holdingKey = null;
		}
		moving = false;
		flicking = false;
		clicking = false;
		if (eatMatches != null) {
			eatMatches = null;
			MinecraftClient.getInstance().options.useKey.setPressed(false);
		}
		if (inventoryWork != null) {
			MinecraftClient client = MinecraftClient.getInstance();
			inventoryWork = null;
			if (openedInventory && client.player != null
					&& (client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.InventoryScreen || chestWork)) {
				client.player.closeHandledScreen();
			}
			openedInventory = false;
			chestWork = false;
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

	private static void press(List<InputUtil.Key> keys, boolean down) {
		if (keys != null) {
			for (InputUtil.Key k : keys) {
				KeyBinding.setKeyPressed(k, down);
			}
		}
	}

	/** One key, or several joined with "+" (e.g. "w+d") that are pressed together. */
	private static List<InputUtil.Key> parseKeys(String name) {
		if (name == null) {
			return null;
		}
		List<InputUtil.Key> keys = new ArrayList<>();
		for (String part : name.split("\\+")) {
			InputUtil.Key k = parseKey(part.trim());
			if (k == null) {
				return null;
			}
			keys.add(k);
		}
		return keys.isEmpty() ? null : keys;
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
			String what = "look".equals(s.action) ? "look at " + s.lookYaw + (s.lookPitch != null ? " / " + s.lookPitch : "")
					: "drop".equals(s.action) ? "drop all " + (s.item != null && s.item.startsWith("name:") ? "items named " + s.item.substring(5) : s.item)
					: "slot".equals(s.action) ? "put the first of " + s.item.replace("|", " / ") + " in hotbar slot " + s.stacks + " and select it"
					: "select".equals(s.action) ? "hold the first of " + s.item.replace("|", " / ") + " (hotbar first, then inventory)"
					: "clicker".equals(s.action) ? (s.clickCps == null || s.clickCps <= 0 ? "stop clicking"
							: "keep " + s.clickButton + " clicking " + trim(s.clickCps) + " times a second")
					: "flick".equals(s.action) ? (s.flickEvery == null || s.flickEvery <= 0 ? "stop the head flick"
							: "head flick between " + s.flickMin + " and " + s.flickMax + " pitch, about every " + trim(s.flickEvery) + "s" + (s.flickSteps != null ? ", " + s.flickSteps + " angles" : ""))
					: "deposit".equals(s.action) ? "put all " + (s.item != null && s.item.startsWith("name:") ? "items named " + s.item.substring(5) : s.item) + " into the chest you're looking at"
					: "chest".equals(s.action) ? "take " + (s.item != null && s.item.startsWith("name:") ? "items named " + s.item.substring(5) : s.item) + " from the chest you're looking at (up to " + (s.stacks > 0 ? s.stacks : 9) + " stacks)"
					: "eat".equals(s.action) ? "eat every " + s.item.replace("name:", "").replace("|", " / ") + " in the inventory"
					: "swapin".equals(s.action) ? "fill the hotbar with " + (s.item != null && s.item.startsWith("name:") ? "items named " + s.item.substring(5) : s.item) + " (swapping out whatever is there)"
					: "stash".equals(s.action) ? "move " + (s.item != null && s.item.startsWith("name:") ? "items named " + s.item.substring(5) : s.item) + " from the hotbar into the inventory"
					: "refill".equals(s.action) ? "move " + (s.stacks > 0 ? s.stacks + " stack" + (s.stacks == 1 ? "" : "s") + " of " : "all ")
							+ (s.item != null && s.item.startsWith("name:") ? "items named " + s.item.substring(5).replace('_', ' ') : s.item) + " to the hotbar"
					: "crafthay".equals(s.action) ? "craft " + (s.stacks > 0 ? s.stacks + " stack" + (s.stacks == 1 ? "" : "s") + " of " : "") + "hay bales into wheat"
							+ (s.wheatBelow > 0 ? " if under " + s.wheatBelow + " wheat" : "")
					: s.key == null ? "pause"
					: s.gotoAxis != null ? "walk " + s.key + " until " + s.gotoAxis + " = " + trim(s.gotoCoord) + (s.gotoSpeed != null ? " (max " + trim(s.gotoSpeed) + " blocks/s)" : "")
					: s.blocks > 0 ? "move " + s.key + " " + trim(s.blocks) + " blocks"
					: s.holdSeconds > 0 ? "hold " + s.key + " for " + trim(s.holdSeconds) + "s"
					: "press " + s.key;
			String number = run > 1 ? (i + 1) + "-" + (i + run) : String.valueOf(i + 1);
			source.sendFeedback(Text.literal(String.format(Locale.ROOT, "  %s. %s, then wait %ss%s",
					number, what, trim(s.waitSeconds), run > 1 ? "  (x" + run + ")" : "")).formatted(Formatting.WHITE));
			i += run - 1;
		}
		source.sendFeedback(Text.literal("  loop: " + (config.loop ? "on" : "off")
				+ "   leave when out: " + (config.leaveItem == null ? "off"
						: (config.leaveItem.startsWith("name:") ? config.leaveItem.substring(5) : config.leaveItem)
						+ (config.leaveBelow > 1 ? " (below " + config.leaveBelow + ")" : ""))
				+ "   /keytimer start [save] [step] | startat <step> | stop | add | hold | move | wait | look | goto | refill | drop | stash | chest | deposit | flick | clicker | select | slot | crafthay | repeat | edit <n> | insert <n> | remove <n> | clear | loop | save | load | saves").formatted(Formatting.DARK_GRAY));
		return 1;
	}

	private interface StepAction {
		int run(com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> ctx, Step step);
	}

	/** Adds the "hold <key> <hold> <wait>", "add <key> <wait>" and "wait <seconds>" forms under {@code node}. */
	private static <T extends com.mojang.brigadier.builder.ArgumentBuilder<FabricClientCommandSource, T>> T stepForms(T node, StepAction action) {
		return node
				.then(literal("hold").then(argument("key", StringArgumentType.string())
						.then(argument("holdSeconds", DoubleArgumentType.doubleArg(0.05, 86400))
								.then(argument("waitSeconds", DoubleArgumentType.doubleArg(0, 86400)).executes(ctx -> {
									String key = StringArgumentType.getString(ctx, "key");
									if (parseKeys(key) == null) {
										ctx.getSource().sendError(Text.literal("Unknown key \"" + key + "\""));
										return 0;
									}
									return action.run(ctx, new Step(key.toLowerCase(Locale.ROOT),
											DoubleArgumentType.getDouble(ctx, "holdSeconds"), DoubleArgumentType.getDouble(ctx, "waitSeconds")));
								})))))
				.then(literal("add").then(argument("key", StringArgumentType.string())
						.then(argument("waitSeconds", DoubleArgumentType.doubleArg(0, 86400)).executes(ctx -> {
							String key = StringArgumentType.getString(ctx, "key");
							if (parseKeys(key) == null) {
								ctx.getSource().sendError(Text.literal("Unknown key \"" + key + "\""));
								return 0;
							}
							return action.run(ctx, new Step(key.toLowerCase(Locale.ROOT), 0, DoubleArgumentType.getDouble(ctx, "waitSeconds")));
						}))))
				.then(literal("wait").then(argument("seconds", DoubleArgumentType.doubleArg(0, 86400)).executes(ctx ->
						action.run(ctx, new Step(null, 0, DoubleArgumentType.getDouble(ctx, "seconds"))))))
				.then(moveForm(action))
				.then(refillForm(action))
				.then(craftHayForm(action))
				.then(lookForm(action))
				.then(dropForm(action))
				.then(stashForm(action))
				.then(literal("eat").then(argument("items", StringArgumentType.string())
						.then(argument("waitSeconds", DoubleArgumentType.doubleArg(0, 86400)).executes(ctx ->
								action.run(ctx, Step.inventory("eat", StringArgumentType.getString(ctx, "items").trim(),
										DoubleArgumentType.getDouble(ctx, "waitSeconds")))))))
				.then(literal("swapin").then(literal("named").then(argument("name", StringArgumentType.string())
						.then(argument("waitSeconds", DoubleArgumentType.doubleArg(0, 86400)).executes(ctx ->
								action.run(ctx, Step.inventory("swapin", "name:" + StringArgumentType.getString(ctx, "name").trim(),
										DoubleArgumentType.getDouble(ctx, "waitSeconds"))))))))
				.then(chestForm(action))
				.then(literal("deposit")
						.then(literal("named").then(argument("name", StringArgumentType.string())
								.then(argument("waitSeconds", DoubleArgumentType.doubleArg(0, 86400)).executes(ctx ->
										action.run(ctx, Step.inventory("deposit", "name:" + StringArgumentType.getString(ctx, "name").trim(),
												DoubleArgumentType.getDouble(ctx, "waitSeconds")))))))
						.then(argument("item", StringArgumentType.string())
								.then(argument("waitSeconds", DoubleArgumentType.doubleArg(0, 86400)).executes(ctx -> {
									String item = StringArgumentType.getString(ctx, "item").toLowerCase(Locale.ROOT);
									if (itemMatcher(item) == null) {
										ctx.getSource().sendError(Text.literal("Unknown item \"" + item + "\". Use deposit named <name> for custom items"));
										return 0;
									}
									return action.run(ctx, Step.inventory("deposit", item, DoubleArgumentType.getDouble(ctx, "waitSeconds")));
								}))))
				.then(flickForm(action))
				.then(clickerForm(action))
				.then(literal("slot").then(argument("slot", IntegerArgumentType.integer(1, 9))
						.then(argument("items", StringArgumentType.string())
								.then(argument("waitSeconds", DoubleArgumentType.doubleArg(0, 86400)).executes(ctx ->
										action.run(ctx, Step.inventory("slot", StringArgumentType.getString(ctx, "items").trim(),
												DoubleArgumentType.getDouble(ctx, "waitSeconds")).withStacks(IntegerArgumentType.getInteger(ctx, "slot"))))))))
				.then(literal("select").then(argument("items", StringArgumentType.string())
						.then(argument("waitSeconds", DoubleArgumentType.doubleArg(0, 86400)).executes(ctx ->
								action.run(ctx, Step.inventory("select", StringArgumentType.getString(ctx, "items").trim(),
										DoubleArgumentType.getDouble(ctx, "waitSeconds")))))))
				.then(gotoForm(action));
	}

	/** "look <yaw> <wait>" or "look <yaw> <pitch> <wait>": turn to face an exact direction (F3 "Facing" numbers). */
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> lookForm(StepAction action) {
		return literal("look")
				.then(literal("here").then(argument("waitSeconds", DoubleArgumentType.doubleArg(0, 86400)).executes(ctx -> {
					// whatever direction you're facing right now
					Step step = Step.inventory("look", null, DoubleArgumentType.getDouble(ctx, "waitSeconds"));
					ClientPlayerEntity p = MinecraftClient.getInstance().player;
					if (p == null) {
						return 0;
					}
					step.lookYaw = Math.round(net.minecraft.util.math.MathHelper.wrapDegrees(p.getYaw()) * 10) / 10f;
					step.lookPitch = Math.round(p.getPitch() * 10) / 10f;
					return action.run(ctx, step);
				})))
				.then(argument("yaw", com.mojang.brigadier.arguments.FloatArgumentType.floatArg(-360, 360))
				.then(argument("waitSeconds", DoubleArgumentType.doubleArg(0, 86400)).executes(ctx -> {
					Step step = Step.inventory("look", null, DoubleArgumentType.getDouble(ctx, "waitSeconds"));
					step.lookYaw = com.mojang.brigadier.arguments.FloatArgumentType.getFloat(ctx, "yaw");
					return action.run(ctx, step);
				})
						.then(argument("waitSeconds2", DoubleArgumentType.doubleArg(0, 86400)).executes(ctx -> {
							// three numbers: yaw, pitch, wait
							Step step = Step.inventory("look", null, DoubleArgumentType.getDouble(ctx, "waitSeconds2"));
							step.lookYaw = com.mojang.brigadier.arguments.FloatArgumentType.getFloat(ctx, "yaw");
							step.lookPitch = (float) Math.max(-90, Math.min(90, DoubleArgumentType.getDouble(ctx, "waitSeconds")));
							return action.run(ctx, step);
						}))));
	}

	/** "drop <item> <wait>" or "drop named <name> <wait>": open the inventory and throw every matching stack. */
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> dropForm(StepAction action) {
		return literal("drop")
				.then(literal("named").then(argument("name", StringArgumentType.string())
						.then(argument("waitSeconds", DoubleArgumentType.doubleArg(0, 86400)).executes(ctx -> {
							String name = StringArgumentType.getString(ctx, "name").trim();
							return action.run(ctx, Step.inventory("drop", "name:" + name, DoubleArgumentType.getDouble(ctx, "waitSeconds")));
						}))))
				.then(argument("item", StringArgumentType.string())
						.then(argument("waitSeconds", DoubleArgumentType.doubleArg(0, 86400)).executes(ctx -> {
							String item = StringArgumentType.getString(ctx, "item").toLowerCase(Locale.ROOT);
							if (itemMatcher(item) == null) {
								ctx.getSource().sendError(Text.literal("Unknown item \"" + item + "\". Use drop named <name> for custom items"));
								return 0;
							}
							return action.run(ctx, Step.inventory("drop", item, DoubleArgumentType.getDouble(ctx, "waitSeconds")));
						})));
	}

	/** "stash <item> <wait>" or "stash named <name> <wait>": move matching hotbar stacks into the main inventory. */
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> stashForm(StepAction action) {
		return literal("stash")
				.then(literal("named").then(argument("name", StringArgumentType.string())
						.then(argument("waitSeconds", DoubleArgumentType.doubleArg(0, 86400)).executes(ctx -> {
							String name = StringArgumentType.getString(ctx, "name").trim();
							return action.run(ctx, Step.inventory("stash", "name:" + name, DoubleArgumentType.getDouble(ctx, "waitSeconds")));
						}))))
				.then(argument("item", StringArgumentType.string())
						.then(argument("waitSeconds", DoubleArgumentType.doubleArg(0, 86400)).executes(ctx -> {
							String item = StringArgumentType.getString(ctx, "item").toLowerCase(Locale.ROOT);
							if (itemMatcher(item) == null) {
								ctx.getSource().sendError(Text.literal("Unknown item \"" + item + "\". Use stash named <name> for custom items"));
								return 0;
							}
							return action.run(ctx, Step.inventory("stash", item, DoubleArgumentType.getDouble(ctx, "waitSeconds")));
						})));
	}

	/** "clicker <left|right> <clicksPerSecond> <wait>" or "clicker off <wait>": keep clicking while the next steps run. */
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> clickerForm(StepAction action) {
		com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> node = literal("clicker");
		node.then(literal("off").then(argument("waitSeconds", DoubleArgumentType.doubleArg(0, 86400)).executes(ctx -> {
			Step step = Step.inventory("clicker", null, DoubleArgumentType.getDouble(ctx, "waitSeconds"));
			step.clickCps = 0.0;
			return action.run(ctx, step);
		})));
		for (String button : List.of("left", "right")) {
			node.then(literal(button).then(argument("cps", DoubleArgumentType.doubleArg(0.5, 40))
					.then(argument("waitSeconds", DoubleArgumentType.doubleArg(0, 86400)).executes(ctx -> {
						Step step = Step.inventory("clicker", null, DoubleArgumentType.getDouble(ctx, "waitSeconds"));
						step.clickButton = button;
						step.clickCps = DoubleArgumentType.getDouble(ctx, "cps");
						return action.run(ctx, step);
					}))));
		}
		return node;
	}

	/** "flick <minPitch> <maxPitch> <everySeconds> <wait>" or "flick off <wait>": random head movement up and down. */
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> flickForm(StepAction action) {
		return literal("flick")
				.then(literal("off").then(argument("waitSeconds", DoubleArgumentType.doubleArg(0, 86400)).executes(ctx -> {
					Step step = Step.inventory("flick", null, DoubleArgumentType.getDouble(ctx, "waitSeconds"));
					step.flickEvery = 0.0;
					return action.run(ctx, step);
				})))
				.then(argument("minPitch", com.mojang.brigadier.arguments.FloatArgumentType.floatArg(-90, 90))
						.then(argument("maxPitch", com.mojang.brigadier.arguments.FloatArgumentType.floatArg(-90, 90))
								.then(argument("everySeconds", DoubleArgumentType.doubleArg(0.05, 600))
										.then(argument("waitSeconds", DoubleArgumentType.doubleArg(0, 86400)).executes(ctx -> flickStep(ctx, action, null))
												.then(argument("angles", IntegerArgumentType.integer(2, 8)).executes(ctx ->
														flickStep(ctx, action, IntegerArgumentType.getInteger(ctx, "angles"))))))));
	}

	private static int flickStep(com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> ctx, StepAction action, Integer angles) {
		Step step = Step.inventory("flick", null, DoubleArgumentType.getDouble(ctx, "waitSeconds"));
		step.flickMin = com.mojang.brigadier.arguments.FloatArgumentType.getFloat(ctx, "minPitch");
		step.flickMax = com.mojang.brigadier.arguments.FloatArgumentType.getFloat(ctx, "maxPitch");
		step.flickEvery = DoubleArgumentType.getDouble(ctx, "everySeconds");
		step.flickSteps = angles;
		return action.run(ctx, step);
	}

	/** "chest named <name> <wait> [stacks]": open the chest you're looking at and take that item until you carry that many stacks. */
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> chestForm(StepAction action) {
		return literal("chest").then(literal("named").then(argument("name", StringArgumentType.string())
				.then(argument("waitSeconds", DoubleArgumentType.doubleArg(0, 86400))
						.executes(ctx -> action.run(ctx, Step.inventory("chest", "name:" + StringArgumentType.getString(ctx, "name").trim(),
								DoubleArgumentType.getDouble(ctx, "waitSeconds"))))
						.then(argument("stacks", IntegerArgumentType.integer(1, 36)).executes(ctx ->
								action.run(ctx, Step.inventory("chest", "name:" + StringArgumentType.getString(ctx, "name").trim(),
										DoubleArgumentType.getDouble(ctx, "waitSeconds")).withStacks(IntegerArgumentType.getInteger(ctx, "stacks"))))))));
	}

	/** "goto <keys> <x|z> <coordinate> <wait>": hold the keys until that F3 coordinate is reached (or you hit a wall). */
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> gotoForm(StepAction action) {
		com.mojang.brigadier.builder.RequiredArgumentBuilder<FabricClientCommandSource, String> keyArg = argument("key", StringArgumentType.string());
		keyArg.suggests((ctx, b) -> net.minecraft.command.CommandSource.suggestMatching(List.of("w", "a", "s", "d", "w+a", "w+d", "a+s", "s+d"), b));
		for (String axis : List.of("x", "z")) {
			keyArg.then(literal(axis).then(argument("coord", DoubleArgumentType.doubleArg(-30_000_000, 30_000_000))
					.then(argument("waitSeconds", DoubleArgumentType.doubleArg(0, 86400)).executes(ctx -> gotoStep(ctx, action, axis, null))
							.then(argument("maxSpeed", DoubleArgumentType.doubleArg(0.2, 20)).executes(ctx ->
									gotoStep(ctx, action, axis, DoubleArgumentType.getDouble(ctx, "maxSpeed")))))));
		}
		return literal("goto").then(keyArg);
	}

	private static int gotoStep(com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> ctx, StepAction action, String axis, Double maxSpeed) {
		String key = StringArgumentType.getString(ctx, "key").toLowerCase(Locale.ROOT);
		if (parseKeys(key) == null) {
			ctx.getSource().sendError(Text.literal("Unknown key \"" + key + "\""));
			return 0;
		}
		Step step = Step.goTo(key, axis, DoubleArgumentType.getDouble(ctx, "coord"), DoubleArgumentType.getDouble(ctx, "waitSeconds"));
		step.gotoSpeed = maxSpeed;
		return action.run(ctx, step);
	}

	/** "refill <item> <waitSeconds>": move every stack of the item from the inventory to the hotbar. */
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> refillForm(StepAction action) {
		return literal("refill")
				.then(literal("named").then(argument("name", StringArgumentType.string())
						.then(argument("waitSeconds", DoubleArgumentType.doubleArg(0, 86400))
								.executes(ctx -> refillNamed(ctx, action, 0))
								.then(argument("stacks", IntegerArgumentType.integer(1, 36))
										.executes(ctx -> refillNamed(ctx, action, IntegerArgumentType.getInteger(ctx, "stacks")))))))
				.then(argument("item", StringArgumentType.string())
						.suggests((ctx, b) -> net.minecraft.command.CommandSource.suggestMatching(List.of("wheat", "hay_block", "carrot", "potato", "beetroot", "named"), b))
						.then(argument("waitSeconds", DoubleArgumentType.doubleArg(0, 86400))
								.executes(ctx -> refill(ctx, action, 0))
								.then(argument("stacks", IntegerArgumentType.integer(1, 36))
										.executes(ctx -> refill(ctx, action, IntegerArgumentType.getInteger(ctx, "stacks"))))));
	}

	/** "refill named <name> <wait> [stacks]": match by the name shown on the item (custom server items). */
	private static int refillNamed(com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> ctx, StepAction action, int stacks) {
		String name = StringArgumentType.getString(ctx, "name").trim();
		if (name.isEmpty()) {
			ctx.getSource().sendError(Text.literal("Give the item's name, e.g. /keytimer refill named Rennet 1"));
			return 0;
		}
		return action.run(ctx, Step.inventory("refill", "name:" + name, DoubleArgumentType.getDouble(ctx, "waitSeconds")).withStacks(stacks));
	}

	private static int refill(com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> ctx, StepAction action, int stacks) {
		String item = StringArgumentType.getString(ctx, "item");
		if (!item.startsWith("name:")) {
			item = item.toLowerCase(Locale.ROOT);
		}
		if (item.equals("name:") || itemMatcher(item) == null) {
			ctx.getSource().sendError(Text.literal("Unknown item \"" + item
					+ "\". Use an item id like wheat, or name:Rennet for an item's display name (underscores for spaces)"));
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
		return literal("move").then(argument("key", StringArgumentType.string())
				.suggests((ctx, b) -> net.minecraft.command.CommandSource.suggestMatching(List.of("w", "a", "s", "d"), b))
				.then(argument("blocks", DoubleArgumentType.doubleArg(0.5, 10000))
						.then(argument("waitSeconds", DoubleArgumentType.doubleArg(0, 86400)).executes(ctx -> {
							String key = StringArgumentType.getString(ctx, "key").toLowerCase(Locale.ROOT);
							for (String part : key.split("\\+")) {
								if (!List.of("w", "a", "s", "d").contains(part)) {
									ctx.getSource().sendError(Text.literal("move only works with w, a, s, d or combinations like w+d"));
									return 0;
								}
							}
							return action.run(ctx, Step.move(key, DoubleArgumentType.getDouble(ctx, "blocks"),
									DoubleArgumentType.getDouble(ctx, "waitSeconds")));
						}))));
	}

	private static String describe(Step s) {
		String what = "look".equals(s.action) ? "look at " + s.lookYaw + (s.lookPitch != null ? " / " + s.lookPitch : "")
					: "drop".equals(s.action) ? "drop all " + (s.item != null && s.item.startsWith("name:") ? "items named " + s.item.substring(5) : s.item)
					: "slot".equals(s.action) ? "put the first of " + s.item.replace("|", " / ") + " in hotbar slot " + s.stacks + " and select it"
					: "select".equals(s.action) ? "hold the first of " + s.item.replace("|", " / ") + " (hotbar first, then inventory)"
					: "clicker".equals(s.action) ? (s.clickCps == null || s.clickCps <= 0 ? "stop clicking"
							: "keep " + s.clickButton + " clicking " + trim(s.clickCps) + " times a second")
					: "flick".equals(s.action) ? (s.flickEvery == null || s.flickEvery <= 0 ? "stop the head flick"
							: "head flick between " + s.flickMin + " and " + s.flickMax + " pitch, about every " + trim(s.flickEvery) + "s" + (s.flickSteps != null ? ", " + s.flickSteps + " angles" : ""))
					: "deposit".equals(s.action) ? "put all " + (s.item != null && s.item.startsWith("name:") ? "items named " + s.item.substring(5) : s.item) + " into the chest you're looking at"
					: "chest".equals(s.action) ? "take " + (s.item != null && s.item.startsWith("name:") ? "items named " + s.item.substring(5) : s.item) + " from the chest you're looking at (up to " + (s.stacks > 0 ? s.stacks : 9) + " stacks)"
					: "eat".equals(s.action) ? "eat every " + s.item.replace("name:", "").replace("|", " / ") + " in the inventory"
					: "swapin".equals(s.action) ? "fill the hotbar with " + (s.item != null && s.item.startsWith("name:") ? "items named " + s.item.substring(5) : s.item) + " (swapping out whatever is there)"
					: "stash".equals(s.action) ? "move " + (s.item != null && s.item.startsWith("name:") ? "items named " + s.item.substring(5) : s.item) + " from the hotbar into the inventory"
					: "refill".equals(s.action) ? "move " + (s.stacks > 0 ? s.stacks + " stack" + (s.stacks == 1 ? "" : "s") + " of " : "all ")
							+ (s.item != null && s.item.startsWith("name:") ? "items named " + s.item.substring(5).replace('_', ' ') : s.item) + " to the hotbar"
					: "crafthay".equals(s.action) ? "craft " + (s.stacks > 0 ? s.stacks + " stack" + (s.stacks == 1 ? "" : "s") + " of " : "") + "hay bales into wheat"
							+ (s.wheatBelow > 0 ? " if under " + s.wheatBelow + " wheat" : "")
					: s.key == null ? "pause"
				: s.gotoAxis != null ? "walk " + s.key + " until " + s.gotoAxis + " = " + trim(s.gotoCoord) + (s.gotoSpeed != null ? " (max " + trim(s.gotoSpeed) + " blocks/s)" : "")
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
		config.leaveItem = saved.leaveItem;
		config.leaveBelow = saved.leaveBelow <= 0 ? 1 : saved.leaveBelow;
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
				&& a.blocks == b.blocks && java.util.Objects.equals(a.action, b.action) && java.util.Objects.equals(a.item, b.item) && a.stacks == b.stacks && a.wheatBelow == b.wheatBelow
				&& java.util.Objects.equals(a.lookYaw, b.lookYaw) && java.util.Objects.equals(a.lookPitch, b.lookPitch)
				&& java.util.Objects.equals(a.gotoAxis, b.gotoAxis) && java.util.Objects.equals(a.gotoCoord, b.gotoCoord) && java.util.Objects.equals(a.gotoSpeed, b.gotoSpeed)
				&& java.util.Objects.equals(a.flickMin, b.flickMin) && java.util.Objects.equals(a.flickMax, b.flickMax) && java.util.Objects.equals(a.flickEvery, b.flickEvery) && java.util.Objects.equals(a.flickSteps, b.flickSteps)
				&& java.util.Objects.equals(a.clickButton, b.clickButton) && java.util.Objects.equals(a.clickCps, b.clickCps);
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

	static void save() {
		try {
			Files.createDirectories(configFile.getParent());
			try (Writer writer = Files.newBufferedWriter(configFile)) {
				GSON.toJson(config, writer);
			}
		} catch (Exception ignored) {
		}
	}
}
