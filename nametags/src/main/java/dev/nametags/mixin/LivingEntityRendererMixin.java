package dev.nametags.mixin;

import dev.nametags.Nametags;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	/** Vanilla never labels the camera entity; allow it for your own player in third person. */
	@Inject(method = "hasLabel(Lnet/minecraft/entity/LivingEntity;D)Z", at = @At("RETURN"), cancellable = true)
	private void nametags$showOwn(LivingEntity entity, double distance, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && Nametags.shouldShowOwn(entity)) {
			cir.setReturnValue(true);
		}
	}
}
