package dev.keytimer.mixin;

import dev.keytimer.KeyTimer;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While a Key Timer route runs, right-clicking with milk never starts drinking (which slows you down
 * and blocks clicks). Clicks on blocks such as cheese vats are sent to the server before this point,
 * so they still work.
 */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class ClientPlayerInteractionManagerMixin {
	@Inject(method = "interactItem", at = @At("HEAD"), cancellable = true)
	private void keytimer$noMilkDrinking(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
		if (KeyTimer.blockMilk(player.getStackInHand(hand))) {
			cir.setReturnValue(ActionResult.PASS);
		}
	}
}
