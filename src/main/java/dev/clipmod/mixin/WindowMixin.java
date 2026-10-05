package dev.clipmod.mixin;

import dev.clipmod.ClipMod;
import net.minecraft.client.util.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Window.class)
public abstract class WindowMixin {
	/** At this point the whole frame (world, HUD, menus) is in the back buffer. */
	@Inject(method = "swapBuffers", at = @At("HEAD"))
	private void clipmod$captureFrame(CallbackInfo ci) {
		ClipMod.onFrameEnd((Window) (Object) this);
	}
}
