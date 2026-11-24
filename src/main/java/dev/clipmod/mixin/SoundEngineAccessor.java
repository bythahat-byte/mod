package dev.clipmod.mixin;

import net.minecraft.client.sound.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SoundEngine.class)
public interface SoundEngineAccessor {
	/** The game's own device opening, used when audio capture isn't possible. */
	@Invoker("openDeviceOrFallback")
	static long clipmod$openDeviceOrFallback(String deviceSpecifier) {
		throw new AssertionError();
	}
}
