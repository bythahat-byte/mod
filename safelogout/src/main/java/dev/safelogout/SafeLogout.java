package dev.safelogout;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

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
	}

	private static Config config = new Config();
	private static Path configFile;

	private static ClientPlayNetworkHandler currentHandler;
	private static ClientWorld currentWorld;
	private static long joinedAt;
	/** Enemies (lower case) who were online when you joined; they don't kick you until they leave and return. */
	private static final Set<String> presentAtJoin = new HashSet<>();
	private static boolean announced;
	private static boolean leaving;

	@Override
	public void onInitializeClient() {
		configFile = FabricLoader.getInstance().getConfigDir().resolve("safelogout.json");
		load();
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
				.then(literal("list").executes(ctx -> status(ctx.getSource()::sendFeedback)))
				.then(literal("on").executes(ctx -> {
					config.enabled = true;
					save();
					// whoever is on right now counts as "already online"
					presentAtJoin.clear();
					presentAtJoin.addAll(onlineEnemies(MinecraftClient.getInstance()));
					ctx.getSource().sendFeedback(Text.literal("Safe Logout is on").formatted(Formatting.GREEN));
					return 1;
				}))
				.then(literal("off").executes(ctx -> {
					config.enabled = false;
					save();
					ctx.getSource().sendFeedback(Text.literal("Safe Logout is off").formatted(Formatting.RED));
					return 1;
				}))));
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
				leaving = false;
			}
			announced = false;
		}
		if (!config.enabled || leaving || config.enemies.isEmpty()) {
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
		presentAtJoin.retainAll(online); // once they log off, their next join counts
		for (String name : online) {
			if (!presentAtJoin.contains(name)) {
				leave(handler, name);
				return;
			}
		}
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
			if (name != null && config.enemies.contains(name)) {
				found.add(name.toLowerCase(Locale.ROOT));
			}
		}
		return found;
	}

	private static int status(java.util.function.Consumer<Text> out) {
		out.accept(Text.literal("Safe Logout is " + (config.enabled ? "on" : "off")).formatted(config.enabled ? Formatting.GREEN : Formatting.RED));
		if (config.enemies.isEmpty()) {
			out.accept(Text.literal("  No enemies yet. Add one with /safelogout add <name>").formatted(Formatting.GRAY));
			return 1;
		}
		Set<String> online = onlineEnemies(MinecraftClient.getInstance());
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
