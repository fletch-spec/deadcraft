package dev.deadcraft.client.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import dev.deadcraft.client.Follow;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Deadlock has the keyboard and mouse while linked, so Minecraft sees no input and would drop to
 * its idle frame rate. Keep the player's normal limit instead.
 */
@Mixin(FramerateLimitTracker.class)
public abstract class FramerateLimitTrackerMixin {
	@Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
	private void deadcraft$noIdleThrottle(CallbackInfoReturnable<Integer> cir) {
		if (Follow.linked()) cir.setReturnValue(Minecraft.getInstance().options.framerateLimit().get());
	}
}
