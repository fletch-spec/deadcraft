package dev.deadcraft.client.mixin;

import dev.deadcraft.client.Follow;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While linked, Deadlock moves the local player; Minecraft's own movement physics must not. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@Inject(method = "travel", at = @At("HEAD"), cancellable = true)
	private void deadcraft$noVanillaMovement(net.minecraft.world.phys.Vec3 input, CallbackInfo ci) {
		if (Follow.linked() && (Object) this instanceof LocalPlayer) ci.cancel();
	}
}
