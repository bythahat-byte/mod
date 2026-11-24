package dev.clipmod.mixin;

import dev.clipmod.audio.AudioCapture;
import net.minecraft.client.sound.SoundEngine;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.IntBuffer;

/** Routes Minecraft's sound through {@link AudioCapture} so clips can include game audio. */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
	@Shadow
	private long devicePointer;

	@Unique
	private boolean clipmod$loopback;
	@Unique
	private String clipmod$requestedDevice;

	@Redirect(method = "init", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/sound/SoundEngine;openDeviceOrFallback(Ljava/lang/String;)J"))
	private long clipmod$openDevice(String deviceSpecifier) {
		clipmod$requestedDevice = deviceSpecifier;
		long loopback = AudioCapture.openLoopback(deviceSpecifier);
		clipmod$loopback = loopback != 0;
		return clipmod$loopback ? loopback : SoundEngineAccessor.clipmod$openDeviceOrFallback(deviceSpecifier);
	}

	/** A loopback device has to be told what format to mix in. */
	@Inject(method = "createAttributes", at = @At("RETURN"), cancellable = true)
	private void clipmod$loopbackAttributes(MemoryStack stack, boolean directionalAudio, CallbackInfoReturnable<IntBuffer> cir) {
		if (!clipmod$loopback) {
			return;
		}
		IntBuffer original = cir.getReturnValue();
		int[] extra = AudioCapture.contextAttributes();
		IntBuffer combined = stack.callocInt(original.remaining() + extra.length + 1);
		for (int i = original.position(); i + 1 < original.limit(); i += 2) {
			int key = original.get(i);
			if (key == 0) {
				break;
			}
			combined.put(key).put(original.get(i + 1));
		}
		combined.put(extra).put(0).flip();
		cir.setReturnValue(combined);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void clipmod$startCapture(String deviceSpecifier, boolean directionalAudio, CallbackInfo ci) {
		if (clipmod$loopback) {
			AudioCapture.start(devicePointer);
		}
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void clipmod$stopCapture(CallbackInfo ci) {
		if (clipmod$loopback) {
			AudioCapture.stop();
		}
	}

	/** The loopback device never "disconnects"; the speakers are handled by Java Sound. */
	@Inject(method = "isDeviceUnavailable", at = @At("HEAD"), cancellable = true)
	private void clipmod$neverUnavailable(CallbackInfoReturnable<Boolean> cir) {
		if (clipmod$loopback) {
			cir.setReturnValue(false);
		}
	}

	/** Report the device the player picked, so the game doesn't keep trying to "switch" to it. */
	@Inject(method = "getCurrentDeviceName", at = @At("HEAD"), cancellable = true)
	private void clipmod$deviceName(CallbackInfoReturnable<String> cir) {
		if (clipmod$loopback) {
			cir.setReturnValue(clipmod$requestedDevice != null ? clipmod$requestedDevice : "Clip Mod audio capture (default output)");
		}
	}
}
