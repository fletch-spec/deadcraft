package dev.deadcraft.client.mixin;

import dev.deadcraft.client.Follow;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Places the player from the Deadlock hero once per frame, before the frame is ticked and drawn. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "runTick", at = @At("HEAD"))
	private void deadcraft$beginFrame(boolean advanceGameTime, CallbackInfo ci) {
		Follow.beginFrame();
	}
}
