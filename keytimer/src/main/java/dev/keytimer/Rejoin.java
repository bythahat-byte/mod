package dev.keytimer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Keeps a running route going through disconnects:
 * - timed out / lost connection: rejoin after a few seconds;
 * - Safe Logout left because someone came online: rejoin after 30 minutes, leave again if they're still on.
 * Rejoining goes through the server's lobby: select the selector item, right-click, click the server.
 */
final class Rejoin {
	private enum State { IDLE, LOST, WAIT, MANUAL, CONNECTING, LOBBY, MENU, TRANSFER, CHECK }

	private static final long SECOND = 1_000_000_000L;

	private static State state = State.IDLE;
	private static long at;
	/** While the player rejoins by hand: when the automatic rejoin would have happened. */
	private static long savedAt;
	/** True when this rejoin came from Safe Logout: check the list before resuming. */
	private static boolean checkPlayers;
	private static boolean safeLogoutPending;
	private static ClientWorld lobbyWorld;
	private static int attempts;
	private static String status = "";

	private Rejoin() {
	}

	static boolean isActive() {
		return state != State.IDLE;
	}

	static void cancel() {
		if (state != State.IDLE) {
			state = State.IDLE;
			KeyTimer.cancelSuspend();
		}
		safeLogoutPending = false;
	}

	static void onSafeLogout() {
		if (KeyTimer.isRunning() && KeyTimer.config().rejoin) {
			safeLogoutPending = true;
		}
	}

	/** Key Timer was running and the world went away. */
	static void onLostWorld(MinecraftClient client) {
		if (!KeyTimer.config().rejoin) {
			KeyTimer.stopForGood();
			return;
		}
		KeyTimer.suspend();
		state = State.LOST;
		at = System.nanoTime() + 5 * SECOND; // give the disconnect screen a moment to appear
	}

	static void tick(MinecraftClient client) {
		if (state == State.IDLE) {
			return;
		}
		long now = System.nanoTime();
		switch (state) {
			case LOST -> {
				if (client.player != null && !safeLogoutPending && !checkPlayers) {
					state = State.IDLE; // only a server switch: carry on
					KeyTimer.resume();
				} else if (client.currentScreen instanceof DisconnectedScreen screen) {
					Text reason = ((dev.keytimer.mixin.DisconnectedScreenAccessor) screen).keytimer$info().reason();
					if (safeLogoutPending) {
						safeLogoutPending = false;
						checkPlayers = true;
						schedule(KeyTimer.config().safeLogoutWaitMinutes * 60L, "Safe Logout left; checking again in "
								+ KeyTimer.config().safeLogoutWaitMinutes + " min");
					} else if (isConnectionProblem(reason)) {
						checkPlayers = false;
						schedule(10, "connection lost (" + reason.getString() + "); rejoining in 10 s");
					} else {
						giveUp("disconnected: " + reason.getString());
					}
				} else if (now > at) {
					giveUp("left the server");
				}
			}
			case WAIT -> {
				if (client.player != null && client.world != null) {
					// you rejoined yourself: carry on once you're through the lobby and in the server
					lobbyWorld = client.world;
					savedAt = at;
					state = State.MANUAL;
					status = "you rejoined; waiting until you're in the server";
					KeyTimer.say(Text.literal("Key Timer: will carry on from step " + "where it stopped once you're in the server"
							+ (checkPlayers ? " (if no one on your list is online)" : "")).formatted(Formatting.YELLOW));
				} else if (now >= at && !(client.currentScreen instanceof ConnectScreen)) {
					connect(client);
				}
			}
			case MANUAL -> {
				if (client.player == null || client.world == null) {
					state = State.WAIT; // left again: go back to waiting for the automatic rejoin
					at = Math.max(savedAt, now + 10 * SECOND);
					status = "waiting to rejoin";
				} else if (client.world != lobbyWorld) {
					arrived(now);
				}
			}
			case CONNECTING -> {
				if (client.player != null && client.world != null) {
					lobbyWorld = client.world;
					state = State.LOBBY;
					at = now + 4 * SECOND; // let the lobby load
					status = "in the lobby";
				} else if (client.currentScreen instanceof DisconnectedScreen || now > at) {
					retry("couldn't connect");
				}
			}
			case LOBBY -> {
				if (client.player == null) {
					retry("lost the lobby");
				} else if (client.world != lobbyWorld) {
					arrived(now); // already sent to the server
				} else if (now >= at) {
					int slot = Math.max(1, Math.min(9, KeyTimer.config().lobbyHotbarSlot)) - 1;
					KeyBinding.onKeyPressed(InputUtil.fromTranslationKey(client.options.hotbarKeys[slot].getBoundKeyTranslationKey()));
					state = State.MENU;
					at = now + SECOND / 2;
				}
			}
			case MENU -> {
				if (client.player == null) {
					retry("lost the lobby");
				} else if (client.world != lobbyWorld) {
					arrived(now);
				} else if (client.currentScreen instanceof HandledScreen<?> && !(client.currentScreen instanceof InventoryScreen)) {
					var handler = client.player.currentScreenHandler;
					int menuSlot = KeyTimer.config().lobbyMenuSlot;
					if (menuSlot < handler.slots.size()) {
						client.interactionManager.clickSlot(handler.syncId, menuSlot, 0, SlotActionType.PICKUP, client.player);
					}
					state = State.TRANSFER;
					at = now + 30 * SECOND;
					status = "joining the server";
				} else if (now >= at) {
					client.interactionManager.interactItem(client.player, Hand.MAIN_HAND); // right-click the selector
					at = now + 3 * SECOND; // try again if the menu doesn't open
				}
			}
			case TRANSFER -> {
				if (client.player == null && !(client.currentScreen instanceof ConnectScreen) && client.currentScreen instanceof DisconnectedScreen) {
					retry("kicked while joining");
				} else if (client.world != null && client.world != lobbyWorld) {
					arrived(now);
				} else if (now > at) {
					if (client.currentScreen instanceof HandledScreen<?>) {
						client.player.closeHandledScreen();
					}
					state = State.LOBBY; // open the menu again
					at = now + 2 * SECOND;
				}
			}
			case CHECK -> {
				if (client.player == null) {
					retry("lost the server");
				} else if (now >= at) {
					if (checkPlayers && listedPlayerOnline()) {
						KeyTimer.say(Text.literal("Key Timer: someone on your Safe Logout list is still online, leaving again for "
								+ KeyTimer.config().safeLogoutWaitMinutes + " min").formatted(Formatting.YELLOW));
						safeLogoutPending = true;
						state = State.LOST;
						at = now + 10 * SECOND;
						client.getNetworkHandler().getConnection().disconnect(Text.literal("Key Timer: someone on your list is still online."));
					} else {
						state = State.IDLE;
						attempts = 0;
						KeyTimer.resume();
					}
				}
			}
			default -> {
			}
		}
	}

	private static void arrived(long now) {
		state = State.CHECK;
		at = now + 5 * SECOND; // let the Tab list fill in and the world load
		status = "in the server";
	}

	private static void schedule(long seconds, String why) {
		state = State.WAIT;
		at = System.nanoTime() + seconds * SECOND;
		status = why;
		dev.keytimer.KeyTimer.logInfo("Rejoin: " + why);
	}

	private static void retry(String why) {
		attempts++;
		long wait = Math.min(300, 15L * attempts); // 15 s, 30 s, ... up to 5 min
		schedule(wait, why + "; trying again in " + wait + " s");
	}

	private static void giveUp(String why) {
		state = State.IDLE;
		KeyTimer.stopForGood();
		dev.keytimer.KeyTimer.logInfo("Rejoin: not rejoining (" + why + ")");
	}

	private static void connect(MinecraftClient client) {
		String address = KeyTimer.config().rejoinServer;
		ServerInfo info = new ServerInfo("Key Timer rejoin", address, ServerInfo.ServerType.OTHER);
		state = State.CONNECTING;
		at = System.nanoTime() + 40 * SECOND;
		status = "connecting to " + address;
		ConnectScreen.connect(new TitleScreen(), client, ServerAddress.parse(address), info, false, null);
	}

	/** Timeouts and dropped connections, not kicks with a message from the server. */
	private static boolean isConnectionProblem(Text reason) {
		if (reason.getContent() instanceof net.minecraft.text.TranslatableTextContent t) {
			String key = t.getKey();
			if (key.equals("disconnect.timeout") || key.equals("disconnect.endOfStream") || key.equals("disconnect.genericReason")
					|| key.equals("disconnect.lost") || key.startsWith("multiplayer.disconnect.server_shutdown")) {
				return true;
			}
		}
		String s = reason.getString().toLowerCase(Locale.ROOT);
		return s.contains("timed out") || s.contains("timeout") || s.contains("connection reset") || s.contains("end of stream")
				|| s.contains("internal exception") || s.contains("connection lost") || s.contains("lost connection");
	}

	/** Asks Safe Logout (if installed) whether anyone on its list is online. */
	private static boolean listedPlayerOnline() {
		try {
			Object result = Class.forName("dev.safelogout.SafeLogout").getMethod("anyListedOnline").invoke(null);
			return Boolean.TRUE.equals(result);
		} catch (Throwable t) {
			return false;
		}
	}

	/** "/keytimer resume": you're in the right place, carry on now (e.g. joined straight into the server). */
	static boolean forceResume() {
		if (state == State.IDLE && !KeyTimer.isSuspended()) {
			return false;
		}
		arrived(System.nanoTime());
		return true;
	}

	static int status(Consumer<Text> out) {
		KeyTimer.Config c = KeyTimer.config();
		out.accept(Text.literal("Auto-rejoin: " + (c.rejoin ? "on" : "off")).formatted(c.rejoin ? Formatting.GREEN : Formatting.RED));
		out.accept(Text.literal("  server " + c.rejoinServer + ", lobby hotbar slot " + c.lobbyHotbarSlot + ", menu slot " + c.lobbyMenuSlot
				+ ", Safe Logout wait " + c.safeLogoutWaitMinutes + " min").formatted(Formatting.GRAY));
		if (state != State.IDLE) {
			long left = Math.max(0, (at - System.nanoTime()) / SECOND);
			out.accept(Text.literal("  now: " + status + (state == State.WAIT ? " (" + left / 60 + "m " + left % 60 + "s left)" : ""))
					.formatted(Formatting.YELLOW));
		}
		out.accept(Text.literal("  /keytimer rejoin on|off|cancel|server <address>|hotbar <1-9>|menuslot <n>|wait <minutes>   /keytimer resume").formatted(Formatting.DARK_GRAY));
		return 1;
	}
}
