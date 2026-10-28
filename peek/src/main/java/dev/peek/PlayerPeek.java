package dev.peek;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Outlines other players through walls (the Glowing effect look) while the peek key is held,
 * or while toggled on. Only use it on servers whose rules allow it.
 */
public final class PlayerPeek implements ClientModInitializer {
	private static KeyBinding peekKey;
	/** false = hold the key to peek, true = press the key to switch peeking on/off. */
	private static boolean toggleMode;
	private static boolean toggledOn;

	@Override
	public void onInitializeClient() {
		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("peek", "main"));
		peekKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.peek.peek", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (peekKey.wasPressed()) {
				if (toggleMode) {
					toggledOn = !toggledOn;
					if (client.player != null) {
						client.player.sendMessage(Text.literal("Player Peek: " + (toggledOn ? "ON" : "OFF"))
								.formatted(toggledOn ? Formatting.GREEN : Formatting.RED), true);
					}
				}
			}
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(literal("peek")
				.executes(ctx -> status(ctx.getSource()))
				.then(literal("hold").executes(ctx -> {
					toggleMode = false;
					toggledOn = false;
					return status(ctx.getSource());
				}))
				.then(literal("toggle").executes(ctx -> {
					toggleMode = true;
					return status(ctx.getSource());
				}))));
	}

	public static boolean active() {
		return toggleMode ? toggledOn : peekKey != null && peekKey.isPressed();
	}

	/** Whether {@code entity} should get an outline right now. */
	public static boolean shouldOutline(Entity entity) {
		return active() && entity instanceof PlayerEntity && entity != MinecraftClient.getInstance().player;
	}

	private static int status(FabricClientCommandSource source) {
		String key = peekKey.getBoundKeyLocalizedText().getString();
		source.sendFeedback(Text.literal("Player Peek: ").formatted(Formatting.GOLD)
				.append(Text.literal(toggleMode
						? "press " + key + " to switch outlines on/off (/peek hold to change)"
						: "hold " + key + " to see players through walls (/peek toggle to change)").formatted(Formatting.WHITE)));
		return 1;
	}
}
