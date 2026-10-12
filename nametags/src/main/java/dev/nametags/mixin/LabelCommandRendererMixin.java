package dev.nametags.mixin;

import dev.nametags.Nametags;
import net.minecraft.client.render.command.LabelCommandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(LabelCommandRenderer.class)
public abstract class LabelCommandRendererMixin {
	/**
	 * Text shadow. Only the second draw call (the solid, in-front label) gets it; the first
	 * is the faded copy that shows through walls.
	 */
	@ModifyArg(method = "render", index = 4, at = @At(value = "INVOKE", ordinal = 1,
			target = "Lnet/minecraft/client/font/TextRenderer;draw"))
	private boolean nametags$shadow(boolean shadow) {
		return shadow || Nametags.config.textShadow;
	}
}
