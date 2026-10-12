package dev.nametags.mixin;

import dev.nametags.Nametags;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.render.command.LabelCommandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(LabelCommandRenderer.Commands.class)
public abstract class LabelCommandsMixin {
	/** Nametag size. */
	@ModifyArgs(method = "add", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/util/math/MatrixStack;scale(FFF)V"))
	private void nametags$scale(Args args) {
		float s = Nametags.config.scale;
		if (s != 1.0f) {
			args.set(0, (float) args.get(0) * s);
			args.set(1, (float) args.get(1) * s);
			args.set(2, (float) args.get(2) * s);
		}
	}

	/** Background darkness. */
	@Redirect(method = "add", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/option/GameOptions;getTextBackgroundOpacity(F)F"))
	private float nametags$background(GameOptions options, float fallback) {
		int opacity = Nametags.config.backgroundOpacity;
		return opacity < 0 ? options.getTextBackgroundOpacity(fallback) : opacity / 100.0f;
	}
}
