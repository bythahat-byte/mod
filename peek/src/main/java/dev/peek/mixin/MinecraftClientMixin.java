package dev.peek.mixin;

import dev.peek.PlayerPeek;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
	/** The same check vanilla uses for Glowing entities and spectator highlighting. */
	@Inject(method = "hasOutline(Lnet/minecraft/entity/Entity;)Z", at = @At("HEAD"), cancellable = true)
	private void peek$outlinePlayers(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (PlayerPeek.shouldOutline(entity)) {
			cir.setReturnValue(true);
		}
	}
}
