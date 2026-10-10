package dev.deadcraft.client.mixin;

import dev.deadcraft.client.DeadlockCamera;
import dev.deadcraft.client.Follow;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While linked, the camera is placed like Deadlock's over-the-shoulder camera instead of Minecraft's:
 * pivot at Deadlock's eye height, shifted over the shoulder, pulled back by Deadlock's camera distance
 * and in again where blocks are in the way. Deadlock aims along its own camera ray, so this is also what
 * keeps Minecraft's crosshair on what abilities hit.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	private Entity entity;
	@Shadow
	private boolean detached;

	@Shadow
	private float xRot;
	@Shadow
	private float yRot;

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Shadow
	protected abstract void setPosition(double x, double y, double z);

	@Shadow
	protected abstract void move(float forward, float up, float right);

	@Shadow
	private float getMaxZoom(float distance) {
		throw new AssertionError();
	}

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void deadcraft$deadlockCamera(float partialTicks, CallbackInfo ci) {
		if (!Follow.linked() || !DeadlockCamera.enabled() || entity == null) return;
		detached = true;
		// A pitch offset for the crosshair, if ever needed (none: see DeadlockCamera.aimPitchOffset).
		setRotation(yRot, xRot + DeadlockCamera.aimPitchOffset());
		// Follow places the player exactly every frame (xo == x), so no partial-tick lerp is needed.
		setPosition(entity.getX(), entity.getY() + DeadlockCamera.eyeHeightBlocks(), entity.getZ());
		move(0f, DeadlockCamera.upBlocks() + DeadlockCamera.aimUpBlocks(), DeadlockCamera.rightBlocks() + DeadlockCamera.aimRightBlocks());  // the third axis points right in 26.3
		move(-getMaxZoom(DeadlockCamera.distanceBlocks()), 0f, 0f);
	}

	/** Deadlock's dash widens the view for a moment; do the same while linked. */
	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void deadcraft$dashKick(float partialTicks, CallbackInfoReturnable<Float> cir) {
		if (Follow.linked()) cir.setReturnValue(cir.getReturnValueF() * DeadlockCamera.fovScale());
	}
}
