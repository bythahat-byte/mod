package dev.chunkreload;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** Reloads all rendered chunks (the same as F3+A) with one key or /reloadchunks. */
public final class ChunkReload implements ClientModInitializer {
	private static KeyBinding reloadKey;

	@Override
	public void onInitializeClient() {
		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("chunkreload", "main"));
		reloadKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.chunkreload.reload", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			boolean pressed = false;
			while (reloadKey.wasPressed()) {
				pressed = true;
			}
			if (pressed) {
				reload(client);
			}
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
				literal("reloadchunks").executes(ctx -> {
					reload(MinecraftClient.getInstance());
					return 1;
				})));
	}

	private static void reload(MinecraftClient client) {
		if (client.world == null || client.worldRenderer == null) {
			return;
		}
		client.worldRenderer.reload();
		if (client.player != null) {
			client.player.sendMessage(Text.literal("Reloading all chunks").formatted(Formatting.YELLOW), true);
		}
	}
}
