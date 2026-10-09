package dev.deadcraft.client.mixin;

import dev.deadcraft.client.DeadlockCamera;
import dev.deadcraft.client.Follow;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Deadlock's dash widens the view for a moment; do the same while linked. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void deadcraft$dashKick(float partialTicks, CallbackInfoReturnable<Float> cir) {
		if (Follow.linked()) cir.setReturnValue(cir.getReturnValueF() * DeadlockCamera.fovScale());
	}
}
