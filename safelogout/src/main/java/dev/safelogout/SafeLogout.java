package dev.safelogout;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Leaves the server as soon as a player on your enemy list comes online (shows up in the Tab list).
 * Enemies who were already online when you joined don't kick you; if they leave and come back, they do.
 */
public final class SafeLogout implements ClientModInitializer {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** The Tab list fills in over the first moments after joining; everyone seen in this window counts as "already online". */
	private static final long SETTLE_NANOS = 3_000_000_000L;

	public static final class Config {
		boolean enabled = true;
		Set<String> enemies = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		/** Admins: you leave after a short delay, and a quick relog by someone already online doesn't count. */
		Set<String> admins = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		double adminDelaySeconds = 3;
		double relogGraceSeconds = 10;
	}

	private static Config config = new Config();
	private static Path configFile;
	private static KeyBinding toggleKey;

	private static ClientPlayNetworkHandler currentHandler;
	private static ClientWorld currentWorld;
	private static long joinedAt;
	/** Enemies (lower case) who were online when you joined; they don't kick you until they leave and return. */
	private static final Set<String> presentAtJoin = new HashSet<>();
	private static boolean announced;
	/** Admins already counted as online who just left, and when (a rejoin within the grace time is ignored). */
	private static final java.util.Map<String, Long> leftAt = new java.util.HashMap<>();
	/** Admins who just came online, and when (you leave once they've stayed for the delay). */
	private static final java.util.Map<String, Long> pendingSince = new java.util.HashMap<>();
	private static boolean leaving;

	@Override
	public void onInitializeClient() {
		configFile = FabricLoader.getInstance().getConfigDir().resolve("safelogout.json");
		load();
		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("safelogout", "main"));
		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.safelogout.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_O, category));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (toggleKey.wasPressed()) {
				setEnabled(!config.enabled, text -> {
					if (client.player != null) {
						client.player.sendMessage(text, true);
					}
				});
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(SafeLogout::tick);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(literal("safelogout")
				.executes(ctx -> status(ctx.getSource()::sendFeedback))
				.then(literal("add").then(argument("name", StringArgumentType.word()).executes(ctx -> {
					String name = StringArgumentType.getString(ctx, "name");
					config.enemies.add(name);
					save();
					ctx.getSource().sendFeedback(Text.literal("Added " + name + " to your enemy list").formatted(Formatting.GREEN));
					return 1;
				})))
				.then(literal("remove").then(argument("name", StringArgumentType.word())
						.suggests((ctx, b) -> net.minecraft.command.CommandSource.suggestMatching(config.enemies, b))
						.executes(ctx -> {
							String name = StringArgumentType.getString(ctx, "name");
							boolean removed = config.enemies.remove(name);
							presentAtJoin.remove(name.toLowerCase(Locale.ROOT));
							save();
							ctx.getSource().sendFeedback(Text.literal(removed ? "Removed " + name : name + " isn't on your list")
									.formatted(removed ? Formatting.GREEN : Formatting.RED));
							return removed ? 1 : 0;
						})))
				.then(literal("admin")
						.then(literal("add").then(argument("name", StringArgumentType.word()).executes(ctx -> {
							String name = StringArgumentType.getString(ctx, "name");
							config.admins.add(name);
							save();
							ctx.getSource().sendFeedback(Text.literal("Added admin " + name + ": you'll leave " + trim(config.adminDelaySeconds)
									+ "s after they join, unless it's a quick relog").formatted(Formatting.GREEN));
							return 1;
						})))
						.then(literal("remove").then(argument("name", StringArgumentType.word())
								.suggests((ctx, b) -> net.minecraft.command.CommandSource.suggestMatching(config.admins, b))
								.executes(ctx -> {
									String name = StringArgumentType.getString(ctx, "name");
									boolean removed = config.admins.remove(name);
									String lower = name.toLowerCase(Locale.ROOT);
									presentAtJoin.remove(lower);
									leftAt.remove(lower);
									pendingSince.remove(lower);
									save();
									ctx.getSource().sendFeedback(Text.literal(removed ? "Removed admin " + name : name + " isn't on your admin list")
											.formatted(removed ? Formatting.GREEN : Formatting.RED));
									return removed ? 1 : 0;
								})))
						.then(literal("delay").then(argument("seconds", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0, 60)).executes(ctx -> {
							config.adminDelaySeconds = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "seconds");
							save();
							ctx.getSource().sendFeedback(Text.literal("You'll leave " + trim(config.adminDelaySeconds) + "s after an admin joins").formatted(Formatting.GREEN));
							return 1;
						})))
						.then(literal("relog").then(argument("seconds", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0, 300)).executes(ctx -> {
							config.relogGraceSeconds = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "seconds");
							save();
							ctx.getSource().sendFeedback(Text.literal("An admin who was already on can relog within " + trim(config.relogGraceSeconds)
									+ "s without you leaving").formatted(Formatting.GREEN));
							return 1;
						}))))
				.then(literal("list").executes(ctx -> status(ctx.getSource()::sendFeedback)))
				.then(literal("on").executes(ctx -> setEnabled(true, ctx.getSource()::sendFeedback)))
				.then(literal("off").executes(ctx -> setEnabled(false, ctx.getSource()::sendFeedback)))
				.then(literal("toggle").executes(ctx -> setEnabled(!config.enabled, ctx.getSource()::sendFeedback)))));
	}

	private static int setEnabled(boolean on, java.util.function.Consumer<Text> feedback) {
		config.enabled = on;
		save();
		if (on) {
			// whoever is on right now counts as "already online"
			presentAtJoin.clear();
			presentAtJoin.addAll(onlineEnemies(MinecraftClient.getInstance()));
			leftAt.clear();
			pendingSince.clear();
		}
		feedback.accept(Text.literal("Safe Logout is " + (on ? "ON" : "OFF")).formatted(on ? Formatting.GREEN : Formatting.RED));
		return 1;
	}

	private static void tick(MinecraftClient client) {
		ClientPlayNetworkHandler handler = client.getNetworkHandler();
		if (handler == null || client.world == null) {
			currentHandler = null;
			currentWorld = null;
			return;
		}
		long now = System.nanoTime();
		if (handler != currentHandler || client.world != currentWorld) {
			// joined a server, or switched server/world through a proxy: start a fresh "already online" window
			boolean newConnection = handler != currentHandler;
			currentHandler = handler;
			currentWorld = client.world;
			joinedAt = now;
			if (newConnection) {
				presentAtJoin.clear();
				leftAt.clear();
				pendingSince.clear();
				leaving = false;
			}
			announced = false;
		}
		if (!config.enabled || leaving || (config.enemies.isEmpty() && config.admins.isEmpty())) {
			return;
		}
		Set<String> online = onlineEnemies(client);
		if (now - joinedAt < SETTLE_NANOS) {
			presentAtJoin.addAll(online);
			return;
		}
		if (!announced) {
			announced = true;
			if (!presentAtJoin.isEmpty() && client.player != null) {
				client.player.sendMessage(Text.literal("Safe Logout: " + String.join(", ", presentAtJoin)
						+ " already online, so you'll stay. You'll be kicked if they leave and come back, or if anyone else on your list joins.")
						.formatted(Formatting.YELLOW), false);
			}
		}
		long grace = (long) (config.relogGraceSeconds * 1e9);
		for (java.util.Iterator<String> it = presentAtJoin.iterator(); it.hasNext(); ) {
			String name = it.next();
			if (online.contains(name)) {
				leftAt.remove(name); // still here, or came back within the grace time
				continue;
			}
			if (!isAdminOnly(name)) {
				it.remove(); // enemies: once they log off, their next join counts
				continue;
			}
			long since = leftAt.computeIfAbsent(name, n -> now);
			if (now - since > grace) {
				it.remove(); // gone longer than a relog: their next join counts
				leftAt.remove(name);
			}
		}
		pendingSince.keySet().retainAll(online); // an admin who left again before the delay doesn't count
		for (String name : online) {
			if (presentAtJoin.contains(name)) {
				continue;
			}
			if (!isAdminOnly(name)) {
				leave(handler, name);
				return;
			}
			long since = pendingSince.computeIfAbsent(name, n -> now);
			if (now - since >= (long) (config.adminDelaySeconds * 1e9)) {
				leave(handler, name);
				return;
			}
		}
	}

	/** On the admin list but not the enemy list (enemies always mean leaving straight away). */
	private static boolean isAdminOnly(String name) {
		return config.admins.contains(name) && !config.enemies.contains(name);
	}

	private static String trim(double d) {
		return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
	}

	private static void leave(ClientPlayNetworkHandler handler, String name) {
		leaving = true;
		SafeLogoutLog.info("Leaving because " + name + " came online");
		handler.getConnection().disconnect(Text.literal("Safe Logout: " + name + " came online, so you left the server."));
	}

	/** Enemy names (lower case) currently in the Tab list. */
	private static Set<String> onlineEnemies(MinecraftClient client) {
		Set<String> found = new HashSet<>();
		ClientPlayNetworkHandler handler = client.getNetworkHandler();
		if (handler == null) {
			return found;
		}
		for (PlayerListEntry entry : handler.getPlayerList()) {
			String name = entry.getProfile().name();
			if (name != null && (config.enemies.contains(name) || config.admins.contains(name))) {
				found.add(name.toLowerCase(Locale.ROOT));
			}
		}
		return found;
	}

	private static int status(java.util.function.Consumer<Text> out) {
		out.accept(Text.literal("Safe Logout is " + (config.enabled ? "on" : "off")).formatted(config.enabled ? Formatting.GREEN : Formatting.RED));
		if (config.enemies.isEmpty() && config.admins.isEmpty()) {
			out.accept(Text.literal("  No one on your lists yet. /safelogout add <name> or /safelogout admin add <name>").formatted(Formatting.GRAY));
			return 1;
		}
		Set<String> online = onlineEnemies(MinecraftClient.getInstance());
		if (!config.admins.isEmpty()) {
			out.accept(Text.literal("  Admins (leave after " + trim(config.adminDelaySeconds) + "s, relogs within "
					+ trim(config.relogGraceSeconds) + "s ignored):").formatted(Formatting.GRAY));
			for (String admin : config.admins) {
				String lower = admin.toLowerCase(Locale.ROOT);
				String state = !online.contains(lower) ? (presentAtJoin.contains(lower) ? "relogging" : "offline")
						: presentAtJoin.contains(lower) ? "online (was on when you joined)"
						: "online";
				out.accept(Text.literal("    " + admin + ": " + state).formatted(online.contains(lower) ? Formatting.YELLOW : Formatting.WHITE));
			}
			if (!config.enemies.isEmpty()) {
				out.accept(Text.literal("  Enemies (leave straight away):").formatted(Formatting.GRAY));
			}
		}
		for (String enemy : config.enemies) {
			String lower = enemy.toLowerCase(Locale.ROOT);
			String state = !online.contains(lower) ? "offline"
					: presentAtJoin.contains(lower) ? "online (was on when you joined, won't kick until they rejoin)"
					: "online";
			out.accept(Text.literal("  " + enemy + ": " + state).formatted(online.contains(lower) ? Formatting.YELLOW : Formatting.WHITE));
		}
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
		Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		if (config.enemies != null) {
			names.addAll(config.enemies);
		}
		config.enemies = names;
		Set<String> admins = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		if (config.admins != null) {
			admins.addAll(config.admins);
		}
		config.admins = admins;
		if (config.relogGraceSeconds <= 0 && config.adminDelaySeconds <= 0) {
			config.adminDelaySeconds = 3;
			config.relogGraceSeconds = 10;
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

	/** Tiny logger wrapper so the reason ends up in latest.log. */
	private static final class SafeLogoutLog {
		private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("safelogout");

		static void info(String message) {
			LOGGER.info(message);
		}
	}
}
