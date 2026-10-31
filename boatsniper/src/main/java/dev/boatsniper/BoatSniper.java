package dev.boatsniper;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.BowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Helpers for the "boat cannon" sniper: an arrow's starting velocity is your aim times the
 * bow speed PLUS your own movement, so when you're launched at huge speed the arrow goes far
 * from your crosshair. This mod shows where it will really go, your speed, and can hold a
 * drawn bow until you're moving forward so shots don't go backwards.
 */
public final class BoatSniper implements ClientModInitializer {
	/** Arrow speed in blocks/tick from a fully drawn bow. */
	private static final double BOW_SPEED = 3.0;

	private static boolean enabled = true;
	private static boolean marker = true;
	private static boolean forwardRelease = false;
	/** Only show the HUD / auto-release above this speed (blocks per second). */
	private static float minSpeed = 20f;

	private static KeyBinding toggleKey;

	@Override
	public void onInitializeClient() {
		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("boatsniper", "main"));
		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.boatsniper.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));

		ClientTickEvents.END_CLIENT_TICK.register(BoatSniper::tick);
		HudElementRegistry.addLast(Identifier.of("boatsniper", "hud"), BoatSniper::render);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(literal("sniper")
				.executes(ctx -> status(ctx.getSource()))
				.then(literal("on").executes(ctx -> { enabled = true; return status(ctx.getSource()); }))
				.then(literal("off").executes(ctx -> { enabled = false; return status(ctx.getSource()); }))
				.then(literal("marker").then(argument("on", BoolArgumentType.bool()).executes(ctx -> {
					marker = BoolArgumentType.getBool(ctx, "on");
					return status(ctx.getSource());
				})))
				.then(literal("forwardrelease").then(argument("on", BoolArgumentType.bool()).executes(ctx -> {
					forwardRelease = BoolArgumentType.getBool(ctx, "on");
					return status(ctx.getSource());
				})))
				.then(literal("minspeed").then(argument("blocksPerSecond", FloatArgumentType.floatArg(0f, 10000f)).executes(ctx -> {
					minSpeed = FloatArgumentType.getFloat(ctx, "blocksPerSecond");
					return status(ctx.getSource());
				})))));
	}

	// ---------------------------------------------------------------- physics

	/** Your movement this tick, in blocks per tick (what the arrow inherits). */
	private static Vec3d movement(ClientPlayerEntity player) {
		Vec3d v = player.getVelocity();
		return player.isOnGround() ? new Vec3d(v.x, 0, v.z) : v;
	}

	/** Bow speed for the current draw (full draw if not drawing). */
	private static double bowSpeed(ClientPlayerEntity player) {
		ItemStack active = player.getActiveItem();
		if (player.isUsingItem() && active.getItem() instanceof BowItem) {
			return BowItem.getPullProgress(player.getItemUseTime()) * BOW_SPEED;
		}
		return BOW_SPEED;
	}

	/** Direction an arrow would start flying if released now. */
	private static Vec3d shotDirection(ClientPlayerEntity player) {
		Vec3d look = player.getRotationVec(1.0f);
		return look.multiply(bowSpeed(player)).add(movement(player));
	}

	// ---------------------------------------------------------------- forward release

	private static void tick(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		while (toggleKey.wasPressed()) {
			enabled = !enabled;
			if (player != null) {
				player.sendMessage(Text.literal("Boat Sniper: " + (enabled ? "ON" : "OFF"))
						.formatted(enabled ? Formatting.GREEN : Formatting.RED), true);
			}
		}
		if (!enabled || !forwardRelease || player == null || client.interactionManager == null) {
			return;
		}
		if (!player.isUsingItem() || !(player.getActiveItem().getItem() instanceof BowItem)) {
			return;
		}
		if (BowItem.getPullProgress(player.getItemUseTime()) < 1.0f) {
			return; // wait for a full draw: damage scales with it
		}
		Vec3d move = movement(player);
		if (move.length() * 20 < minSpeed) {
			return; // not launched yet; let the player hold the bow
		}
		// release only while moving the way you're facing, so the arrow can't go backwards
		Vec3d look = player.getRotationVec(1.0f);
		if (move.dotProduct(look) > 0 && shotDirection(player).dotProduct(look) > 0) {
			client.interactionManager.stopUsingItem(player);
		}
	}

	// ---------------------------------------------------------------- HUD

	private static void render(DrawContext context, RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity player = client.player;
		if (!enabled || player == null || client.options.hudHidden) {
			return;
		}
		double speed = movement(player).length() * 20; // blocks per second
		if (speed < minSpeed) {
			return;
		}
		Vec3d look = player.getRotationVec(1.0f);
		Vec3d shot = shotDirection(player);
		boolean forward = shot.dotProduct(look) > 0;

		int w = context.getScaledWindowWidth();
		int h = context.getScaledWindowHeight();
		String info = String.format(Locale.ROOT, "%.0f b/s  shot: %s", speed, forward ? "FORWARD" : "BACKWARD");
		context.drawTextWithShadow(client.textRenderer, Text.literal(info), w / 2 - client.textRenderer.getWidth(info) / 2,
				h / 2 + 14, forward ? 0xFF55FF55 : 0xFFFF5555);

		if (!marker || !forward) {
			return;
		}
		// project the shot direction onto the screen (vertical FOV from options; set
		// "FOV Effects" to 0% in Accessibility so speed doesn't stretch the view)
		Vec3d up = new Vec3d(0, 1, 0);
		Vec3d right = look.crossProduct(up);
		if (right.lengthSquared() < 1e-6) {
			right = new Vec3d(1, 0, 0);
		}
		right = right.normalize();
		Vec3d camUp = right.crossProduct(look).normalize();
		Vec3d d = shot.normalize();
		double depth = d.dotProduct(look);
		if (depth <= 0.01) {
			return;
		}
		double fov = Math.toRadians(client.options.getFov().getValue());
		double focal = (h / 2.0) / Math.tan(fov / 2);
		int sx = (int) Math.round(w / 2.0 + d.dotProduct(right) / depth * focal);
		int sy = (int) Math.round(h / 2.0 - d.dotProduct(camUp) / depth * focal);
		int red = 0xFFFF3030;
		context.fill(sx - 5, sy, sx + 6, sy + 1, red);
		context.fill(sx, sy - 5, sx + 1, sy + 6, red);
		context.drawStrokedRectangle(sx - 3, sy - 3, 7, 7, red);
	}

	private static int status(FabricClientCommandSource source) {
		source.sendFeedback(Text.literal("Boat Sniper: " + (enabled ? "ON" : "OFF")).formatted(Formatting.GOLD));
		source.sendFeedback(Text.literal("  shot marker: " + (marker ? "on" : "off") + "   /sniper marker true|false").formatted(Formatting.GRAY));
		source.sendFeedback(Text.literal("  forward release: " + (forwardRelease ? "on" : "off") + "   /sniper forwardrelease true|false").formatted(Formatting.GRAY));
		source.sendFeedback(Text.literal(String.format(Locale.ROOT, "  min speed: %.0f b/s   /sniper minspeed <b/s>", minSpeed)).formatted(Formatting.GRAY));
		return 1;
	}
}
